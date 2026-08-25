# MayaReCharm

## [5.3.0] - 2026-08-25
### Added
- Per-Maya-SDK Python stubs selection with `none`, `types-maya-strict`, and `maya-stubs`

### Changed
- Updated compatibility to PyCharm 2026.2
- Custom MayaSdk AdditionalData support to bypass internalApi restriction on `PythonSdkAdditionalData`

### Removed
- MayaReCharm run configuration and related localization entries

## [5.2.2] - 2026-07-17
### Changed
- Update build configuration for IntelliJ platform compatibility

## [5.2.1] - 2026-07-14
### Revert
- Update Python dependency to PythonCore for intellijPlatform 262 compatibility

## [5.2.0] - 2026-07-08
### Added
- Execute Document in Maya directly from each Maya log tab toolbar
- Automatic Maya log tab closing when an SDK port is edited successfully

### Changed
- Maya log tab workflow now restores user-opened tabs, keeps the selected-tab state, and improves tab reopening UX
- SDK port editing now validates duplicate ports across registered Maya SDKs

### Removed
- MayaReCharm run configuration type and related run/debug runners

## [5.1.1] - 2026-07-06
### Fixed
- Update Python dependency to PythonCore for intellijPlatform 262 compatibility

## [5.1.0] - 2026-05-20
### Added
- Log console shows Maya Script Editor commands history in a custom level, lower than debug.
- Log message coloring adapts to theme feel (light/dark).
- Log filter levels can be toggled independently.

### Fixed
- Maintain scroll position after applying log level filter.

### Changed
- kResult message now shown as debug log instead of info.

## [5.0.1] - 2026-05-18
### Fixed
- Fix Maya SDK mapping incompatibility with intellijPlatform 262

## [5.0.0] - 2026-05-06
### Added
- Reconnect action and multi-SDK tab support in the Maya log tool window

- Refactored attach/debug plumbing and modernized `attach_pydevd.py` argument parsing
- Refined Maya SDK naming/detection helpers and execute-action SDK resolution
- Improved Maya log wiring and `open_log.py` startup behavior with cleaner initialization output
- Updated settings UI/layout internals and project service wiring cleanup
- `Execute actions` send code to the Maya instance based on the current selected interpreter
- Clarified `scanForMayapy` documentation for Maya directory matching

### Fixed
- Additional plugin verifier compatibility cleanups

## [4.0.0] - 2026-04-13
### Added
- Custom dialog for adding Maya Python SDK with auto-detection of Maya installations
- PyCharm 2026.1 support

### Changed
- Updated icons for actions and tool windows
- Refactored log path handling to use system temp directory
- Upgraded to Gradle 9.4.1, Kotlin 2.3.20, and IntelliJ Platform Plugin 2.14.0
- Simplified folder chooser descriptor logic and replaced deprecated methods

## [3.4.0] - 2025-04-20
### Added
- Log console with colored output for warn and debug log levels
- Display actions in log console toolbar
- Export log from Maya with timestamp and severity level

### Changed
- Use original `attach_script.py` from pydev

### Fixed
- Log file clear with custom ClearAll action in log console

## [3.3.0] - 2025-04-19
### Changed
- Migrated to Gradle 8 and Kotlin/JVM 21
- Updated all deprecated code
- PyCharm 2025 support

## [3.2.7] - 2023-09-22
### Fixed
- Now properly recognized UTF-8 code points in remotely executed scripts

## [3.2.6] - 2022-02-31
### Added
- PyCharm 2021.3 support

## [3.2.5] - 2021-08-03
### Added
- PyCharm 2021.2 support

## [3.2.4] - 2021-04-08
### Added
- Support Python 3

## [3.2.3] - 2021-04-07
### Added
- PyCharm 2021 support

## [3.2.2] - 2021-03-17
### Added
- Display of Maya path in the "Attach to process popup"

### Fixed
- Issue where the proper mayaSdk port number was not always selected when attaching to a Maya instance

## [3.2.1] - 2021-03-13
### Added
- Support for PyCharm 2020.3

### Changed
- Updated gradle and build system

## [3.1.2] - 2020-04-05
### Added
- Improved handling and detection of MayaPy version when adding new interpreters

### Fixed
- Fixed issues where MayaCharm could not detect Maya instances that were launched via script and not executed directly

## [3.1.1] - 2019-09-30
### Added
- Now forces utf8 encoding when executing the selection

### Changed
- All strings have been moved to a resource bundle for localization purposes

### Fixed
- Settings panel properly updates with Maya SDKs now when interpreter settings are changed
- Settings panel now has proper add and remove buttons for Maya SDKs that will properly add or remove the interpreter as well setup the command port info for the SDK

### Removed
- Removed debug run config, since it was unreliable due to a race condition when it would execute your Maya code

## [3.0.2] - 2019-03-31
### Fixed
- Fixed issue with execute commands being greyed out in PyCharm 2019.1

## [3.0.1] - 2019-01-13
### Added
- Attach to Process now shows Maya instances you can attach to

### Fixed
- Fixed bug that prevented MayaCharm from finding Maya instances when launched with arguments

## [3.0.0] - 2018-11-27
### Added
- Ported to Kotlin
- Better support for multiple Maya installs
- Removed dependencies on PyCharm Professional's remote debugger as well as PyCharm Professional

[5.3.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.3.0
[5.2.2]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.2.2
[5.2.1]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.2.1
[5.2.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.2.0
[5.1.1]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.1.1
[5.1.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.1.0
[5.0.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v5.0.0
[4.0.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v4.0.0
[3.4.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v3.4.0
[3.3.0]: https://github.com/mathbou/MayaReCharm/releases/tag/v3.3.0
[3.2.7]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.7
[3.2.6]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.6
[3.2.5]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.5
[3.2.4]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.4
[3.2.3]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.3
[3.2.2]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.2
[3.2.1]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.2.1
[3.1.2]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.1.2
[3.1.1]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.1.1
[3.0.0]: https://github.com/cmcpasserby/MayaCharm/releases/tag/v3.0.0
