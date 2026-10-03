# Источник AltStore для Lumina Reader на iPhone

Адрес источника (добавляется в AltStore: «Источники» → «+»):

```
https://raw.githubusercontent.com/IvanZagulin/lumina-reader/automation/altstore-source/source.json
```

Как это устроено:

- каждый push в `main`, который затрагивает `shared/`, `sharedUi/`, `iosApp/`, `altstore/` или сборку Gradle,
  а также ручной запуск, выполняет `.github/workflows/ios-release.yml`;
- workflow собирает неподписанный `LuminaReader-1.0.<N>.ipa` (N — номер запуска) и публикует его
  как предварительный выпуск `ios-v1.0.<N>`; такой выпуск никогда не помечается как «latest»
  и не содержит `.apk`, поэтому Android-обновление его не видит;
- `update_source.py` дописывает новую версию в начало `source.json` (хранятся последние пять),
  а workflow кладёт файл в ветку `automation/altstore-source`;
- AltStore подписывает приложение вашим Apple ID при установке и при обновлениях.

Статические поля (название, описание, значок, цвет) лежат в `source-template.json`;
`{repository}` заменяется на `владелец/репозиторий`. Проверка скрипта:
`python3 -m unittest discover -s altstore`.
