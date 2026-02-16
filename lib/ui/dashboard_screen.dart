import 'dart:async';
import 'package:flutter/material.dart';
import 'package:cunning_document_scanner/cunning_document_scanner.dart';
import 'package:sensors_plus/sensors_plus.dart';
import 'editor_screen.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  bool _isStable = true;
  StreamSubscription? _gyroSub;

  @override
  void initState() {
    super.initState();
    _gyroSub = gyroscopeEventStream().listen((GyroscopeEvent event) {
      double velocity = event.x.abs() + event.y.abs() + event.z.abs();
      if ((velocity < 0.3) != _isStable) {
        setState(() => _isStable = velocity < 0.3);
      }
    });
  }

  @override
  void dispose() {
    _gyroSub?.cancel();
    super.dispose();
  }

  Future<void> _triggerScan() async {
    try {
      // 这里的配置允许一次拍多张
      List<String>? pictures = await CunningDocumentScanner.getPictures();
      
      if (pictures != null && pictures.isNotEmpty) {
        if (!mounted) return;
        Navigator.push(
          context,
          MaterialPageRoute(
            // 【关键修改】传入整个列表
            builder: (_) => EditorScreen(imagePaths: pictures), 
          ),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("ERR: $e")));
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _isStable ? const Color(0xFF00FF41) : const Color(0xFFFF3333);
    return Scaffold(
      appBar: AppBar(title: const Text("NULLSCAN // LAZARUS")),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            GestureDetector(
              onTap: _isStable ? _triggerScan : null,
              child: Container(
                width: 120, height: 120,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: color, width: 2),
                  color: _isStable ? color.withOpacity(0.1) : Colors.transparent,
                ),
                child: Icon(Icons.camera_alt_outlined, size: 48, color: color),
              ),
            ),
            const SizedBox(height: 30),
            Text(_isStable ? "SYSTEM READY" : "STABILIZE DEVICE", style: TextStyle(color: color, letterSpacing: 4.0)),
            const SizedBox(height: 10),
            const Text("[ MULTI-PAGE MODE ENABLED ]", style: TextStyle(color: Colors.grey, fontSize: 10)),
          ],
        ),
      ),
    );
  }
}