<div align="center">

# ⚡ Pulse

**A High-Performance, Privacy-First Mobile Scanner — rebuilt in Kotlin + Jetpack Compose**
<br>
*极致离线 · 视觉重构 · 像素主权*

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com)
[![Framework](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![CV_Engine](https://img.shields.io/badge/CV_Engine-OpenCV_4-5C2D91?style=for-the-badge&logo=opencv&logoColor=white)](https://opencv.org)
[![ML_Engine](https://img.shields.io/badge/ML_Engine-Google_ML_Kit-FF6F00?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/ml-kit)

*Pulse is the full native rewrite of NULLSCAN — the project codename "Project Pulse" has taken over as the product name.*

</div>

---

## 📖 Introduction (简介)

**Pulse** is a no-compromise, **100% offline** document scanner focused on paper restoration
(试卷还原): removing red/blue pen ink, de-penciling, illumination normalization and
OCR-aware text protection — all computed locally on your device.

The v2.0 rewrite moves from Flutter to a fully native **Kotlin + Jetpack Compose (Material 3)**
stack, keeps the exact Lazarus restoration pipeline, and adds a self-built camera scanner,
manual crop, an encrypted archive and richer exports.

**Pulse** 是一款拒绝妥协的**全离线**移动端扫描工具，专注纸张还原：去除红/蓝笔迹、去铅笔痕、
光照归一化与 OCR 文字保护——全部计算在你的设备本地完成。2.0 重制版从 Flutter 迁移到原生
**Kotlin + Jetpack Compose (Material 3)**，完整保留 Lazarus 还原管线，并新增自研相机扫描、
手动裁剪、加密文档库与更丰富的导出能力。

---

## ✨ Features (功能亮点)

### 🧪 Hybrid CV Pipeline (混合视觉流水线)
- **Default scan path: Google Document Scanner** — when Google Play services is available,
  Pulse launches the system scanner UI (auto-capture, auto-crop, multi-page). The scanner is
  a Play-services Dynamite module, **not bundled into the APK**; Pulse itself still holds no
  INTERNET permission (module delivery is GMS Core's own channel). Unavailable / cancelled /
  offline? Pulse falls back seamlessly to its built-in manual scanner.
  （默认走 Google 系统扫描器：自动拍摄/裁剪/多页。扫描模块由 GMS Core 按需分发、不打入
  APK；本应用依旧不申请 INTERNET 权限。GMS 不可用或用户取消时无缝回退自研扫描页。）
- **Aether Lens** — real-time document edge detection on the CameraX analysis stream (OpenCV
  Canny + largest-quad contour), plus a draggable four-corner crop with perspective warp.
  （基于分析流的实时边缘检测 + 可拖拽四角裁剪与透视矫正）
- **Lazarus Engine** — the exact legacy pipeline, now in Kotlin/OpenCV: HSV ink separation
  (red wraps the hue circle in two segments), per-channel illumination normalization,
  gray-threshold stroke erase (De-Pencil / De-Ink), **OCR-zone forced recall**, USM sharpen.
  （原版管线逐函数移植：HSV 去红蓝、光照归一化、去铅笔/去黑笔、OCR 保护区召回、USM 锐化）
- **OCR Protect** — ML Kit on-device Chinese OCR bounding boxes force text pixels to survive
  aggressive erase passes. （ML Kit 中文 OCR 圈定文字保护区，强力去墨不误删文字）

### 🛡️ Absolute Privacy (绝对隐私)
- **Air-gapped by design** — the app does not request the `INTERNET` permission. Not even once.
  （原生不申请网络权限，实现物理级隔离）
- **Encrypted archive** — saved documents are AES-256-GCM encrypted with an Android Keystore
  key, unlockable behind BiometricPrompt. （文档库 Keystore 加密 + 生物识别锁）
- **Zero-Metadata** — we process images, not your identity. （只处理像素，不触碰元数据）

### 🗂️ Rebuilt Experience (重制体验)
- Material 3 UI: dynamic color, tonal surfaces, segmented controls, bottom sheets.
- Multi-page editor with reorder / rotate / delete, tag taxonomy (23 paper tags).
- Exports: adaptive-size **PDF** (optional watermark), **PNG/JPG** to gallery, **OCR text**.
- Library with thumbnail grid and title / tag / **OCR full-text** search.

## 🛠️ Tech Stack (技术栈)

| Component | Technology |
| :--- | :--- |
| **UI** | Kotlin · Jetpack Compose · Material 3 (dynamic color) |
| **Computer Vision** | OpenCV 4 (`org.opencv:opencv` AAR) |
| **Machine Learning** | ML Kit Text Recognition — Chinese (bundled, offline model) |
| **Camera** | CameraX (Preview / ImageAnalysis / ImageCapture) |
| **Storage** | Room + DataStore · Android Keystore (AES-256-GCM) |

## 🚀 Build (构建)

```bash
# Requires JDK 17 + Android SDK (minSdk 26 / target 35)
./gradlew assembleDebug
```

Open the project in Android Studio, or run on-device:

```bash
./gradlew installDebug
```

## 📄 License

Licensed under **Apache License 2.0**.
**The software is provided "AS IS".** 😋

<br>

<div align="center">

*Project Pulse: Refactoring the world, one pixel at a time.*
<br>
*Rewritten with ❤️ in Kotlin — successor of [NULLSCAN](https://github.com/Linkium-suki/NULLSCAN)*

</div>
