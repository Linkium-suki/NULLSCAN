import 'dart:isolate';
import 'dart:typed_data';
import 'dart:ui';
import 'package:opencv_dart/opencv_dart.dart' as cv;

class ProcessRequest {
  final Uint8List imageBytes;
  final List<Rect> protectedZones; 
  final bool removeRed;
  final bool removeBlue;
  // 0: 原图 (只去红蓝)
  // 1: 去铅笔 (去除浅色污渍)
  // 2: 去黑笔 (强力去除细笔画)
  final int erosionKernelSize; 

  ProcessRequest({
    required this.imageBytes,
    required this.protectedZones,
    this.removeRed = true,
    this.removeBlue = true,
    this.erosionKernelSize = 1,
  });
}

class LazarusEngine {
  static Future<Uint8List> process(ProcessRequest req) async {
    return await Isolate.run(() => _coreAlgorithm(req));
  }

  static Uint8List _coreAlgorithm(ProcessRequest req) {
    // 1. 解码
    final mat = cv.imdecode(req.imageBytes, cv.IMREAD_COLOR);

    // 2. 墨迹分离 (HSV 去除红蓝)
    final hsv = cv.cvtColor(mat, cv.COLOR_BGR2HSV);
    
    cv.Mat scalarToMat(double v1, double v2, double v3) {
       return cv.Mat.fromScalar(1, 1, cv.MatType.CV_8UC3, cv.Scalar(v1, v2, v3, 0));
    }

    if (req.removeRed) {
      final mask1 = cv.inRange(hsv, scalarToMat(0, 40, 40), scalarToMat(10, 255, 255));
      final mask2 = cv.inRange(hsv, scalarToMat(170, 40, 40), scalarToMat(180, 255, 255));
      final redMask = cv.add(mask1, mask2); 
      mat.setTo(cv.Scalar(255, 255, 255, 0), mask: redMask);
    }

    if (req.removeBlue) {
      final blueMask = cv.inRange(hsv, scalarToMat(100, 40, 40), scalarToMat(130, 255, 255));
      mat.setTo(cv.Scalar(255, 255, 255, 0), mask: blueMask);
    }

    // ============================================================
    // 3. Skyfsm 光照校正
    // ============================================================
    final channels = cv.split(mat);
    
    int kSizeVal = (mat.cols / 30).round();
    if (kSizeVal % 2 == 0) kSizeVal++;
    if (kSizeVal < 15) kSizeVal = 15;
    final structElement = cv.getStructuringElement(cv.MORPH_RECT, (kSizeVal, kSizeVal));

    for (int i = 0; i < channels.length; i++) {
      final bg = cv.Mat.empty();
      cv.morphologyEx(channels[i], cv.MORPH_CLOSE, structElement, dst: bg);
      cv.divide(channels[i], bg, scale: 255, dst: channels[i]);
    }
    cv.merge(channels, dst: mat);

    if (req.erosionKernelSize == 0) {
      final (success, encoded) = cv.imencode(".jpg", mat);
      return success ? encoded : req.imageBytes;
    }

    // ============================================================
    // 4. 黑笔/铅笔去除系统
    // ============================================================
    
    final gray = cv.cvtColor(mat, cv.COLOR_BGR2GRAY);
    
    final double threshVal = req.erosionKernelSize == 2 ? 160.0 : 200.0;
    
    // [修复 1] 取元组的第2个元素 (Mat)
    final threshBinary = cv.threshold(gray, threshVal, 255, cv.THRESH_BINARY).$2;

    // 4.3 构造 "保留掩膜"
    final whiteMat = cv.Mat.fromScalar(gray.rows, gray.cols, cv.MatType.CV_8UC1, cv.Scalar(255, 0, 0, 0));
    
    // 此时 threshBinary 已经是 Mat 类型，不会报错
    final contentMask = cv.subtract(whiteMat, threshBinary);

    // 4.4 形态学过滤
    if (req.erosionKernelSize == 2) {
      final morphKernel = cv.getStructuringElement(cv.MORPH_RECT, (3, 3));
      cv.morphologyEx(contentMask, cv.MORPH_OPEN, morphKernel, dst: contentMask);
    }

    // 4.5 OCR 强制召回
    for (var rect in req.protectedZones) {
      int x = rect.left.toInt();
      int y = rect.top.toInt();
      int w = rect.width.toInt();
      int h = rect.height.toInt();
      
      x = x.clamp(0, contentMask.cols - 1);
      y = y.clamp(0, contentMask.rows - 1);
      if (x + w > contentMask.cols) w = contentMask.cols - x;
      if (y + h > contentMask.rows) h = contentMask.rows - y;

      if (w > 0 && h > 0) {
        final roiRect = cv.Rect(x, y, w, h);
        final grayRoi = gray.region(roiRect);
        
        // [修复 2] 同样取元组的第2个元素
        final textRoi = cv.threshold(grayRoi, 200, 255, cv.THRESH_BINARY_INV).$2;
        
        final targetRoi = contentMask.region(roiRect);
        
        final temp = cv.add(targetRoi, textRoi);
        temp.copyTo(targetRoi);
      }
    }

    // 4.6 应用掩膜
    final finalMask = contentMask;
    final eraseMask = cv.subtract(whiteMat, finalMask);
    
    mat.setTo(cv.Scalar(255, 255, 255, 0), mask: eraseMask);

    // 5. USM 锐化
    final blurred = cv.Mat.empty();
    cv.gaussianBlur(mat, (0, 0), 3, dst: blurred);
    cv.addWeighted(mat, 1.5, blurred, -0.5, 0, dst: mat);

    // 6. 输出
    final (success, encoded) = cv.imencode(".jpg", mat);
    return success ? encoded : req.imageBytes;
  }
}