КОНТРАКТЫ:
- RecordingViewModel: управление записью, транскрибацией и сохранением
- SettingsViewModel: хранение настроек (модель, язык) через DataStore
- StreamingScreen: UI записи в реальном времени
- SettingsScreen: UI настроек

ЗАВИСИМОСТИ:
- RecordingViewModel зависит от SettingsViewModel (инъекция через фабрику)
- MainActivity создаёт SettingsViewModel первым, затем передаёт в RecordingViewModelFactory

ИСТОРИЯ ИЗМЕНЕНИЙ:
08.08.2026 - Интеграция SettingsViewModel в RecordingViewModel:
  * RecordingViewModel теперь получает SettingsViewModel через конструктор
  * MainActivity использует RecordingViewModelFactory для создания RecordingViewModel
  * Настройки модели и языка применяются динамически