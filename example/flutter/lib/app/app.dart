import 'package:flutter/material.dart';
import 'package:lottie_example/lottie_view/lottie_view_page.dart';

/// {@template app}
/// Root application widget.
/// {@endtemplate}
class App extends StatelessWidget {
  /// {@macro app}
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: LottieViewPage(),
    );
  }
}
