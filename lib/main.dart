import 'dart:io'; // <--- 必须放在最上面
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'ui/dashboard_screen.dart';

void main() {
  // 确保系统绑定初始化
  WidgetsFlutterBinding.ensureInitialized();
  // 强制竖屏
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  // 设置状态栏颜色
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
  ));
  
  runApp(const NullScanApp());
}

class NullScanApp extends StatelessWidget {
  const NullScanApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'NULLSCAN',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF050505), // 深黑背景
        primaryColor: const Color(0xFF00FF41), // 终端绿
        // 使用等宽字体模拟终端风格，这里使用系统自带的 Monospace
        fontFamily: Platform.isIOS ? 'Courier' : 'monospace',
        
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.black,
          elevation: 0,
          titleTextStyle: TextStyle(
            fontFamily: 'monospace', 
            fontWeight: FontWeight.bold, 
            fontSize: 18, 
            letterSpacing: 2
          ),
        ),
        
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00FF41),
          secondary: Colors.white,
          surface: Color(0xFF121212),
        ),
        
        sliderTheme: const SliderThemeData(
          trackHeight: 2,
          thumbShape: RoundSliderThumbShape(enabledThumbRadius: 6),
        ),
        
        useMaterial3: true,
      ),
      home: const DashboardScreen(),
    );
  }
}

