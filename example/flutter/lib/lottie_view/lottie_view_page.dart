import 'package:flutter/material.dart';
import 'package:lottie/lottie.dart';

/// {@template lottie_view_page}
/// Full-screen looping Lottie confetti animation.
/// {@endtemplate}
class LottieViewPage extends StatelessWidget {
  /// {@macro lottie_view_page}
  const LottieViewPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      body: Center(
        child: Lottie.asset(
          'assets/confetti.json',
          width: double.infinity,
          fit: BoxFit.fitWidth,
          repeat: true,
        ),
      ),
    );
  }
}
