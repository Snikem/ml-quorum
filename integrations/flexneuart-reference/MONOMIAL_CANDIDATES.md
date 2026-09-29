# Проверка поиска и подключение провайдера

Откройте `MonoforestSearchDemo.java` в IDE и запустите `main`.
Вверху файла задаются `MODEL_PATH`, `INDEX_DIR`, `QUERY`, `THRESHOLD`, `TOP_K`, `SHOW_TOP`.
Модель — экспорт `linear_monomials.json` из `FactorFactory/model.ipynb`.

Программа использует `MonoforestCandidateProvide` и выводит время поиска, количество
кандидатов, ID, исходный линейный score, вероятность, заголовок и фрагмент текста.
`RELEVANT_ID` позволяет проверить позицию конкретного документа в выбранном top-k.
Настройки можно переопределить VM options, например:

```text
-Dmonoforest.query="grammar terms medicine"
-Dmonoforest.threshold=0.0
-Dmonoforest.topK=100
-Dmonoforest.showTop=10
```

Условие строгое: `intercept + sum(trained_coef * monomial) > threshold`.
Порог не является вероятностью. `TOP_K` — максимальное количество результатов;
выдача может быть меньше. Отрицательные коэффициенты сохраняются.
Низкий порог и большое число мономов могут приводить к долгому поиску.

## FlexNeuART

Параметры candidate provider:

```text
cand_prov       = monoforest
cand_prov_uri   = /Volumes/Ex_Volume/msmarcoProcces/lucene_index_positions
cand_prov_add_conf = /Users/snikem/proga/FlexNeuART/scripts/monoforest/monomial_candidate_provider.json
```

Это имена параметров; используйте синтаксис запуска вашего экспериментального скрипта.
`queryFieldName` в конфиге — имя текстового поля во входном запросе FlexNeuART
(например, `text` или `text_unlemm`). `indexFieldName` и `titleFieldName` — поля индекса.
`modelPath` — абсолютный путь или путь относительно рабочей директории Java-процесса.
`cand_prov_uri` разрешается относительно resource root, как у обычного Lucene-провайдера.

Провайдер загружает модель и индекс один раз, поддерживает параллельные поиски и
возвращает `CandidateInfo` / `CandidateEntry` с исходным score (float по API FlexNeuART).
`mNumFound`, как у Lucene-провайдера, для больших выдач может быть нижней оценкой.
В подробном `search()` и демо дополнительно есть `total_hits_relation`.
Для ручного использования закрывайте провайдер через try-with-resources после завершения всех поисков:

```java
try (MonoforestCandidateProvide provider = new MonoforestCandidateProvide(
        indexDir, modelPath, 0.0, "text", "text", "title")) {
    CandidateInfo result = provider.getCandidates("grammar terms medicine", 1000);
}
```

Проверки:

```bash
mvn -f java/pom.xml -Dtest=MonoforestCandidateProvideTest,MonomialCandidateSearchTest test
```
