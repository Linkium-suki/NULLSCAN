import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import '../core/lazarus_engine.dart';

class EditorScreen extends StatefulWidget {
  final List<String> imagePaths; 
  const EditorScreen({super.key, required this.imagePaths});

  @override
  State<EditorScreen> createState() => _EditorScreenState();
}

class _EditorScreenState extends State<EditorScreen> {
  int _currentIndex = 0;
  
  // Caches
  final Map<int, Uint8List> _originalBytesCache = {};
  final Map<int, Uint8List> _processedBytesCache = {};
  final Map<int, RecognizedText> _ocrCache = {};

  // UI State
  bool _isPageLoading = false;
  bool _isExporting = false;
  String? _statusMessage;

  // Params
  bool _enableLazarus = true;
  bool _enableOcrProtect = true;
  bool _removeRed = true;
  bool _removeBlue = true;
  double _erosionLevel = 1.0; // 0:Original, 1:Soft, 2:Clear

  // Naming
  final List<String> _availableTags = [
    "语文", "数学", "英语", "物理", "化学", "生物", 
    "历史", "地理", "政治", "信息", "通用",
    "初一", "初二", "初三", "高一", "高二", "高三",
    "试卷", "笔记", "错题", "答案", "一模", "二模"
  ];
  final Set<String> _selectedTags = {};

  @override
  void initState() {
    super.initState();
    _loadCurrentPage();
  }

  Future<void> _loadCurrentPage() async {
    setState(() {
      _isPageLoading = true;
      _statusMessage = "LOADING...";
    });

    try {
      // 1. Load Original
      if (!_originalBytesCache.containsKey(_currentIndex)) {
        final file = File(widget.imagePaths[_currentIndex]);
        _originalBytesCache[_currentIndex] = await file.readAsBytes();
      }

      // 2. Run OCR (Async)
      if (!_ocrCache.containsKey(_currentIndex)) {
        _runOcrForIndex(_currentIndex);
      }

      // 3. Process
      if (_processedBytesCache.containsKey(_currentIndex)) {
        setState(() => _isPageLoading = false);
      } else {
        await _runPipeline();
      }

    } catch (e) {
      debugPrint("Load Error: $e");
      setState(() => _isPageLoading = false);
    }
  }

  Future<void> _runOcrForIndex(int index) async {
    try {
      final inputImage = InputImage.fromFilePath(widget.imagePaths[index]);
      final textRecognizer = TextRecognizer(script: TextRecognitionScript.chinese);
      final results = await textRecognizer.processImage(inputImage);
      _ocrCache[index] = results;
      textRecognizer.close();
      
      if (index == _currentIndex && _enableLazarus && _enableOcrProtect && mounted) {
        _runPipeline();
      }
    } catch (e) {
      debugPrint("OCR Error: $e");
    }
  }

  Future<void> _runPipeline() async {
    if (!_enableLazarus) {
      setState(() => _isPageLoading = false);
      return;
    }

    if (!_isExporting) setState(() => _isPageLoading = true);

    try {
      final original = _originalBytesCache[_currentIndex]!;
      List<Rect> zones = [];
      
      if (_enableOcrProtect) {
        final ocrData = _ocrCache[_currentIndex];
        if (ocrData != null) {
          zones = ocrData.blocks.map((b) => b.boundingBox).toList();
        }
      }

      final req = ProcessRequest(
        imageBytes: original,
        protectedZones: zones,
        removeRed: _removeRed,
        removeBlue: _removeBlue,
        erosionKernelSize: _erosionLevel.toInt(),
      );

      final result = await LazarusEngine.process(req);
      _processedBytesCache[_currentIndex] = result;

      if (mounted && !_isExporting) setState(() => _isPageLoading = false);
    } catch (e) {
      debugPrint("Engine Error: $e");
      if (mounted) setState(() => _isPageLoading = false);
    }
  }

  void _changePage(int newIndex) {
    if (newIndex < 0 || newIndex >= widget.imagePaths.length) return;
    setState(() => _currentIndex = newIndex);
    _loadCurrentPage();
  }

  void _onParamChanged() {
    _processedBytesCache.clear(); // 清除所有缓存，强制重算
    _runPipeline();
  }

  // --- Export Logic ---

  Future<void> _exportPdf() async {
    setState(() {
      _isExporting = true;
      _statusMessage = "BATCH PROCESSING...";
    });

    final pdf = pw.Document();

    try {
      for (int i = 0; i < widget.imagePaths.length; i++) {
        setState(() => _statusMessage = "PROCESSING PAGE ${i + 1}/${widget.imagePaths.length}");
        
        Uint8List imageBytes;

        // Ensure data exists
        if (!_originalBytesCache.containsKey(i)) {
           final file = File(widget.imagePaths[i]);
           _originalBytesCache[i] = await file.readAsBytes();
        }

        if (_enableLazarus) {
          // If not cached, process now
          if (_processedBytesCache.containsKey(i)) {
            imageBytes = _processedBytesCache[i]!;
          } else {
            // Run silent OCR if needed
            List<Rect> zones = [];
            if (_enableOcrProtect) {
               final inputImage = InputImage.fromFilePath(widget.imagePaths[i]);
               final textRecognizer = TextRecognizer(script: TextRecognitionScript.chinese);
               final ocrResult = await textRecognizer.processImage(inputImage);
               zones = ocrResult.blocks.map((b) => b.boundingBox).toList();
               textRecognizer.close();
            }
            final req = ProcessRequest(
              imageBytes: _originalBytesCache[i]!,
              protectedZones: zones,
              removeRed: _removeRed,
              removeBlue: _removeBlue,
              erosionKernelSize: _erosionLevel.toInt(),
            );
            imageBytes = await LazarusEngine.process(req);
            _processedBytesCache[i] = imageBytes;
          }
        } else {
          imageBytes = _originalBytesCache[i]!;
        }

        // --- 核心修复：PDF 自适应图片尺寸 ---
        final decoded = await decodeImageFromList(imageBytes);
        
        // 创建完全匹配图片尺寸的页面，margin设为0
        final customFormat = PdfPageFormat(
          decoded.width.toDouble(), 
          decoded.height.toDouble(),
          marginAll: 0,
        );

        final imageProvider = pw.MemoryImage(imageBytes);

        pdf.addPage(pw.Page(
          pageFormat: customFormat,
          margin: pw.EdgeInsets.zero,
          build: (pw.Context context) {
            return pw.Stack(
              fit: pw.StackFit.expand,
              children: [
                pw.Image(imageProvider, fit: pw.BoxFit.cover),
                pw.Positioned(
                  bottom: 0, right: 0,
                  child: pw.Text("PAGE ${i+1} | NULLSOFT", style: const pw.TextStyle(fontSize: 24, color: PdfColors.grey500)),
                ),
              ],
            );
          },
        ));
      } 

      final fileName = _generateFileName(ext: 'pdf');
      await Printing.sharePdf(bytes: await pdf.save(), filename: fileName);

    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Export Error: $e")));
    } finally {
      if (mounted) setState(() { _isExporting = false; _statusMessage = null; });
    }
  }

  String _generateFileName({required String ext}) {
    final timestamp = DateFormat('yyyyMMdd_HHmm').format(DateTime.now());
    final tags = _selectedTags.isEmpty ? "BATCH" : _selectedTags.join("-");
    return "NS_${tags}_$timestamp.$ext";
  }

  // --- UI ---

  @override
  Widget build(BuildContext context) {
    final currentBytes = _enableLazarus ? _processedBytesCache[_currentIndex] : _originalBytesCache[_currentIndex];
    
    if (currentBytes == null && !_originalBytesCache.containsKey(_currentIndex)) {
       return const Scaffold(body: Center(child: CircularProgressIndicator(color: Color(0xFF00FF41))));
    }

    return Scaffold(
      appBar: AppBar(
        title: Text("EDITOR (${_currentIndex + 1}/${widget.imagePaths.length})"),
        actions: [
          IconButton(
            icon: const Icon(Icons.save_alt, color: Color(0xFF00FF41)),
            onPressed: !_isExporting ? _exportPdf : null,
          ),
        ],
      ),
      body: Stack(
        children: [
          Column(
            children: [
              _buildNamingSystem(),
              
              Expanded(
                child: Container(
                  color: Colors.black26,
                  width: double.infinity,
                  child: Stack(
                    children: [
                      Center(
                        child: currentBytes != null
                            ? InteractiveViewer(
                                minScale: 0.5, maxScale: 5.0,
                                child: Image.memory(currentBytes, fit: BoxFit.contain),
                              )
                            : Container(),
                      ),
                      Positioned(
                        top: 10, left: 10,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          color: Colors.black54,
                          child: Text(
                            _enableLazarus ? "LAZARUS: ON" : "ORIGINAL",
                            style: TextStyle(
                              color: _enableLazarus ? const Color(0xFF00FF41) : Colors.yellow,
                              fontSize: 10, fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // Page Navigation
              Container(
                height: 48,
                color: const Color(0xFF1E1E1E),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.arrow_back_ios, size: 16),
                      onPressed: _currentIndex > 0 ? () => _changePage(_currentIndex - 1) : null,
                    ),
                    Text(
                      "PAGE ${_currentIndex + 1} OF ${widget.imagePaths.length}",
                      style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.bold),
                    ),
                    IconButton(
                      icon: const Icon(Icons.arrow_forward_ios, size: 16),
                      onPressed: _currentIndex < widget.imagePaths.length - 1 ? () => _changePage(_currentIndex + 1) : null,
                    ),
                  ],
                ),
              ),

              _buildControlPanel(),
              _buildFooter(),
            ],
          ),

          if (_isPageLoading || _isExporting)
            Container(
              color: Colors.black54,
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const CircularProgressIndicator(color: Color(0xFF00FF41)),
                    const SizedBox(height: 16),
                    Text(
                      _statusMessage ?? "PROCESSING...",
                      style: const TextStyle(color: Colors.white, fontFamily: 'monospace', letterSpacing: 2),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildNamingSystem() {
    return Container(
      constraints: const BoxConstraints(maxHeight: 120),
      color: const Color(0xFF111111),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: SingleChildScrollView(
        child: Wrap(
          spacing: 8, runSpacing: 0,
          children: _availableTags.map((tag) {
            final isSelected = _selectedTags.contains(tag);
            return FilterChip(
              label: Text(tag, style: TextStyle(fontSize: 10, color: isSelected ? Colors.black : Colors.white)),
              selected: isSelected,
              onSelected: (selected) {
                setState(() => selected ? _selectedTags.add(tag) : _selectedTags.remove(tag));
              },
              backgroundColor: Colors.black,
              selectedColor: const Color(0xFF00FF41),
              checkmarkColor: Colors.black,
              padding: EdgeInsets.zero,
              materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            );
          }).toList(),
        ),
      ),
    );
  }

  Widget _buildControlPanel() {
    return Container(
      color: const Color(0xFF111111),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Switch(
                    value: _enableLazarus,
                    activeColor: const Color(0xFF00FF41),
                    onChanged: (v) {
                      setState(() => _enableLazarus = v);
                      _onParamChanged();
                    },
                  ),
                  const Text("ENGINE", style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                ],
              ),
              Row(
                children: [
                  Text("OCR PROTECT", style: TextStyle(fontSize: 10, color: Colors.grey)),
                  Switch(
                    value: _enableOcrProtect,
                    activeTrackColor: Colors.blueAccent,
                    onChanged: (v) {
                      setState(() => _enableOcrProtect = v);
                      _onParamChanged();
                    },
                  ),
                ],
              ),
            ],
          ),
          if (_enableLazarus) ...[
            Row(
              children: [
                const Text("FILTER: ", style: TextStyle(color: Colors.grey, fontSize: 10)),
                FilterChip(
                  label: const Text("RED", style: TextStyle(fontSize: 10)),
                  selected: _removeRed,
                  onSelected: (v) { setState(() => _removeRed = v); _onParamChanged(); },
                  selectedColor: Colors.redAccent.withOpacity(0.5),
                  checkmarkColor: Colors.white,
                  visualDensity: VisualDensity.compact,
                ),
                const SizedBox(width: 8),
                FilterChip(
                  label: const Text("BLUE", style: TextStyle(fontSize: 10)),
                  selected: _removeBlue,
                  onSelected: (v) { setState(() => _removeBlue = v); _onParamChanged(); },
                  selectedColor: Colors.blueAccent.withOpacity(0.5),
                  checkmarkColor: Colors.white,
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
            Row(
              children: [
                const Text("STYLE: ", style: TextStyle(color: Colors.grey, fontSize: 10)),
                Expanded(
                  child: SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 2,
                      thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                      overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
                    ),
                    child: Slider(
                      value: _erosionLevel,
                      min: 0, max: 2, divisions: 2,
                      // 文案修改以匹配新功能
                      label: ["Original", "Soft(De-Pencil)", "Clear(De-Ink)"][_erosionLevel.toInt()],
                      activeColor: const Color(0xFF00FF41),
                      inactiveColor: Colors.white12,
                      onChanged: (v) => setState(() => _erosionLevel = v),
                      onChangeEnd: (v) => _onParamChanged(),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildFooter() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 4),
      color: Colors.black,
      alignment: Alignment.center,
      child: Text(
        "POWERED BY NULLSOFT CRYSTAL & LAZARUS",
        style: TextStyle(color: Colors.white.withOpacity(0.3), fontSize: 8, letterSpacing: 1.5, fontFamily: 'monospace'),
      ),
    );
  }
}