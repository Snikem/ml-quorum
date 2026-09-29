# Monoforest

Самостоятельный Java/Maven-проект: поиск по линейным мономам, извлечение фичей,
индексация MS MARCO и генерация обучающих датасетов. Не зависит от FlexNeuART.
Пакеты проекта начинаются с `monoforest`. Требуются JDK 11+ и Maven.
Lucene 8.6.0 оставлен для совместимости с существующими индексами.

## Открыть и собрать

В IntelliJ IDEA откройте `pom.xml` как отдельный проект.
Рабочая папка конфигураций запуска — корень этого проекта.

```bash
cd /Users/snikem/proga/monoforest
mvn test
mvn package
```

Результат: `target/monoforest-0.1.0-SNAPSHOT.jar` (библиотека без встроенных зависимостей).
Обычные тесты используют временные данные и не требуют внешнего диска.
`mvn test -Pindex-tests` дополнительно запускает тесты внешнего индекса;
при недоступном индексе эти проверки пропускаются.

## Что перенесено

| Возможность | Классы / пакеты |
| --- | --- |
| Поиск Monoforest | `MonoforestSearch`, `MonoforestSearchDemo`, `impl.MonomialCandidateSearch`, `impl.MonomialCandidateQuery`, `impl.MonomialFeatureQueries` |
| BM25 и сравнение выдачи | `Bm25Search`, `impl.testSearch`, `impl.TestBM25Standalone`, `impl.Top1000` |
| Генерация и обслуживание индекса | `impl.StandaloneMsMarcoIndexer`, `impl.LuceneIndexManager`, `impl.MsMarcoRawReader` |
| Новые фичи и семейства | `impl.features`, включая `AllFeaturesExtractor` |
| Старые фичи | `impl.document`, `impl.query`, `impl.joint`, `FactorManager`, `FeatureVectorBuilder` |
| Датасеты и потоки примеров | `DatasetOrchestrator`, `DatasetOrchestrator2`, `DatasetOrchestrator3`, `MakePositiveStream`, `MakeRandomNegativeStream`, `MakeBm25Stream` |
| Дамперы и хранилища фичей | `DocFeatureDumper`, `FullFeatureDumper`, `ParallelFeatureDumper`, `QueryFeatureDumper`, `DocFeatureStore` |
| Прочие утилиты | Все остальные исходные Java-файлы, включая сплиттеры, инспекторы, CatBoost inference и экспериментальные программы |
| Ресурсы | POS-модель `en-pos-maxent.bin`, словарь `words_alpha.txt`, локальная `.env` |
| Тесты | Все тесты ядра и фичей, адаптированные тесты поискового сервиса, проверка индексации |

Полное соответствие исходных файлов новым путям: [docs/MIGRATION.json](docs/MIGRATION.json).

## Запуски из IDE

- Поиск: `monoforest.MonoforestSearchDemo.main()`.
- Создание индекса: `monoforest.impl.StandaloneMsMarcoIndexer.main()`.
- Новый датасет: `monoforest.impl.DatasetOrchestrator3.main()`.
- Сравнение с BM25: `monoforest.impl.testSearch.main()`.

Настройки старых утилит и `DatasetOrchestrator3` остаются в начале классов,
как до переноса. Подробности генерации: [docs/DATASET.md](docs/DATASET.md).
Индексатор сохраняет режим CREATE: его ручной запуск пересоздаёт выбранный индекс.
Для другого входа/выхода можно передать VM options
`-Dmonoforest.input=/path/to/gz -Dmonoforest.index=/path/to/new/index`.

## Конфигурация

В проект перенесена локальная `.env` с вашими настройками; она исключена из Git.
Для другой машины скопируйте `.env.example` в `.env` и укажите свои пути.
`AppConfig` читает настройки в порядке: JVM property с именем ключа →
переменная окружения → `.env` текущей рабочей папки.
`-Dmonoforest.config=/path/to/.env` позволяет выбрать файл явно.

Поисковый demo поддерживает `-Dmonoforest.model=...`, `-Dmonoforest.index=...`,
`-Dmonoforest.query=...`, `-Dmonoforest.threshold=3.0`, `-Dmonoforest.topK=10`.
Модель также задаётся через `MONOFOREST_MODEL_PATH` в `.env`.
Данные MS MARCO, существующие индексы и обученная модель остаются по своим
внешним путям; они не копируются внутрь исходников.

## Java API и будущее подключение

```java
import monoforest.MonoforestSearch;

try (MonoforestSearch search = new MonoforestSearch(
        "/path/to/index", "/path/to/linear_monomials.json", 3.0, "text", "title")) {
    var result = search.search("grammar terms medicine", 10);
    System.out.println(result.toPrettyString());
}
```

Результат содержит `candidates` с `doc_id`, `score`, `probability`, а также
`total_hits`, `total_hits_relation` и `lucene_query`. Порог применяется строго:
`score > threshold`. Один экземпляр можно использовать для параллельных запросов;
закрывать его следует после завершения всех запросов.

Когда понадобится подключение к FlexNeuART, установите библиотеку:

```bash
mvn install
```

И добавьте в будущий адаптер зависимость:

```xml
<dependency>
  <groupId>io.github.snikem</groupId>
  <artifactId>monoforest</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Адаптер на стороне FlexNeuART будет преобразовывать результаты в `CandidateInfo`.
Артефакт пока локальный и не опубликован в Maven Central.
Старые интеграционные файлы сохранены в `integrations/flexneuart-reference/`
для справки и не входят в сборку. Их API и команды относятся к прежней интеграции.

В исходном FlexNeuART оставлена резервная рабочая копия с незакоммиченными изменениями.
Разработку самостоятельного решения следует продолжать здесь.
