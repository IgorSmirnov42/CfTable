# Подготовка к использованию

* Положите в папку `src/main/resources` файл `credentials.json`, сгенерированный по ссылке https://developers.google.com/sheets/api/quickstart/java#step_1_turn_on_the. Там надо нажать на кнопку Enable the Google Sheets API, ввести имя приложения (придумайте любое), выбрать Desktop App и нажать Download Client Configuration

* Создайте в папке `src/main/resources` файл `secrets.json` и положите туда ключ и секрет от Codeforces в json-формате. Ключ и секрет можно сгенерировать здесь: https://codeforces.com/settings/api

Например:
```
{
  "key": "239239239",
  "secret": "424242"
}
```

# Запуск

`gradle run --args="НОМЕР_КОНТЕСТА"`

Вместо `gradle` подставьте `./gradlew`, если Вы используете Linux и `gradlew.bat`, если Windows

Пример:

`gradle run --args="325665"`

В папке `results` появится файл с результатами контеста
