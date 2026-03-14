# Changelog

## [1.3.1] - 2026-03-14

### Fixed

- Install script hanging when upgrading from versions without `--version` support
- Portable `sed` pattern for parsing GitHub API tag names

## [1.3.0] - 2026-03-14

### Added

- `--version` / `-v` flag to display installed version
- Auto-update support in `install.sh` -- detects existing installs and updates in place

## [1.2.0] - 2026-03-14

### Added

- iOS, Android, Flutter, and ClojureDart example projects
- MIT license

### Fixed

- Image asset dimension updates for Android lottie renderer compatibility

## [1.1.0] - 2026-03-09

### Added

- Test suite and CI test job

### Fixed

- `--help` flag handling before input validation
- ImageMagick 7 portable binary in CI

## [1.0.0] - 2026-03-09

### Added

- Initial lottie animation optimizer
- CI/CD release workflow and install script
- Image downscaling and WebP conversion
- Float precision truncation
- Editor metadata stripping
- Framerate reduction
- Lossless mode
