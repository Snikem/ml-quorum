# Тесты семейств признаков

Команды выполняются из корня проекта Monoforest.

```sh
mvn test
```

Запускает JUnit-тесты `*Test`, включая фиксированные коллекции для TF-IDF,
BM25, BM25 top-n, документных и запросных фичей. Внешний индекс не нужен.
Для запросных фичей используется POS-модель `en-pos-maxent.bin` из ресурсов проекта.
Проверки TSV работают с временным файлом, который удаляется после теста.

```sh
mvn test -Dtest=QueryFeaturesTest
mvn test -Dtest=DocumentFeaturesTest
```

Можно также запускать классы и отдельные методы `@Test` в IDE.
Несовпадение эталона или исключение завершает тест и Maven с ошибкой.

## Проверки на внешнем индексе

```sh
mvn test -Pindex-tests
mvn test -Pindex-tests -Dtest=TFIDFFamilyIT
```

Профиль добавляет шесть классов `*IT`: TF-IDF, BM25, BM25 top-n,
точные совпадения в body/title и неупорядоченные окна.
Они читают существующий индекс, указанный в `AppConfig`, и не перестраивают его.
Недоступный индекс отмечается как skipped. Ошибки доступного индекса и расхождения значений завершают тест ошибкой.
Для проверки title индекс должен поддерживать поиск отдельных слов в этом поле.

Для TF-IDF/BM25 можно изменить запрос, порог и размер выборки:

```sh
mvn test -Dtest=TFIDFFamilyIT -Dmonoforest.testQuery="cat dog" -Dmonoforest.testThreshold=1 -Dmonoforest.testMaxHits=100
```

Для BM25 top-n доступны `monoforest.testQuery` и `monoforest.testTopN`.
В обычный запуск `mvn test` классы `*IT` не входят; явный `-Dtest=...IT`
позволяет запустить выбранный интеграционный тест без профиля.
