# Один запрос из JupyterHub

Нужны Linux, Java 11+ и три загруженных на сервер объекта:
`monoforest-0.1.0-SNAPSHOT-runner.jar`, папка готового Lucene-индекса
и `linear_monomials.json`. Maven на сервере не нужен.
Готовый notebook: `notebooks/one_query.ipynb`.

Команда (также работает в Terminal JupyterHub):

```bash
java -Xmx4g -jar monoforest-0.1.0-SNAPSHOT-runner.jar \
  --index /path/to/lucene_index_positions \
  --model /path/to/linear_monomials.json \
  --ram-root /dev/shm \
  --query "grammar terms medicine" \
  --threshold 3 --top-k 10
```

JAR копирует текущий committed индекс в собственную временную папку tmpfs,
открывает модель и индекс, выполняет **ровно один запрос**, выводит JSON
с ID, score, probability, title и полным text найденных документов.
После завершения RAM-копия удаляется. Исходные файлы индекса сохраняются.
Индексатор должен быть остановлен: во время копирования берётся write lock.
Поля по умолчанию: `text`, `title`; другие имена задаются `--text-field`, `--title-field`.
Если тексты не хранились в индексе, соответствующие значения будут `null`.
Порог строгий: `score > threshold`; пустая выдача допустима.

Время в ответе:

- `copy_to_ram_ms` — подготовка и копирование индекса в tmpfs, включая проверку копии.
- `open_model_and_index_ms` — открытие модели мономов и индекса.
- `search_ms` — один поиск и получение текстов документов, измеренные `System.nanoTime()`.

В `search_ms` входят вычисления фичей, ранжирование, объяснения score и чтение
stored полей. Сериализация/печать JSON, передача в notebook и старт JVM не входят.
Прогрева и повторов нет. Ленивая загрузка ресурсов фичей, JIT и GC, попавшие
в первый запрос, включены в его время. Один замер не является средней скоростью;
исходный файловый кеш не сбрасывается, поэтому это также не гарантированно
«холодный диск». Без `--ram-root` поиск работает по исходному индексу.

## RAM

128 ГБ на сервере не обязательно доступны процессу JupyterHub. Перед копированием
проверяются свободное место tmpfs, MemAvailable и видимые ограничения cgroup.
При расчёте cgroup учитывается оценка освобождаемого чистого дискового кеша:
`file - shmem - file_dirty - file_writeback - unevictable`, с нижней границей 0.
Поэтому заполненный дисковым кешем контейнер не считается полностью занятым.
Анонимная память, tmpfs, грязные и закреплённые страницы не добавляются в доступную
память. При недоступной/неполной статистике применяется прежняя строгая оценка.
Сохраняются пределы всех видимых родительских cgroup и MemAvailable хоста;

оставляется место под Java heap (`-Xmx4g`) и резерв 12 GiB (`--reserve-gib`).
Резерв — оценка, не гарантия при параллельной нагрузке. Если `/dev/shm` мал,
нужен другой доступный tmpfs нужного размера или изменение лимита администратором.
JAR сам ничего не монтирует и не меняет лимиты сервера.

[Linux tmpfs](https://www.kernel.org/doc/html/latest/filesystems/tmpfs.html)
может использовать swap. Для строго RAM-only эксперимента нужен tmpfs без swap
(опция `noswap`, если поддерживается сервером) или сервер без активного swap.
`-Xmx4g` относится к Java heap: сам индекс хранится в tmpfs, вне heap.

Сборка JAR на машине разработчика:

```bash
mvn -Prunner package
```

Результат: `target/monoforest-0.1.0-SNAPSHOT-runner.jar`.

## Настройки вашего JupyterHub

По вашей диагностике: лимит контейнера 120 GiB, `/dev/shm` — 64 MiB,
а `/run/jupyter-monitor` — tmpfs на 120 GiB. В notebook подставлены ваш индекс,
Java 11 и собственная папка `/run/jupyter-monitor/monoforest-anglukhikhan`.
Используется `-Xmx4g`, чтобы оставить дополнительный запас рядом с индексом 96.46 GiB.

Исправленный JAR выводит в stderr строку `RAM preflight: ... clean file cache included`.
Копирование всё равно требует реально доступных ресурсов; это оценка, а не резервирование.
Не требуется менять conda-среду, писать в `memory.reclaim` или сбрасывать кеш хоста.
Определения полей: [документация cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html).

## Частоты слов обучающих запросов

Исходный `docv2_train_queries.tsv` (12.89 MiB) включён в JAR как ресурс
`/monoforest/docv2_train_queries.tsv`. Это тот же файл, который использовался
локально для QueryMinLogFrequency, QueryMaxLogFrequency и QueryMeanLogFrequency.
Для обычного запуска дополнительно загружать TSV не нужно. При сборке на другом
компьютере в Git должен быть добавлен файл
`src/main/resources/monoforest/docv2_train_queries.tsv`.

Если нужен внешний TSV, укажите JVM option **перед `-jar`**:

```bash
java -Xmx4g -Dmonoforest.queryFrequencyTsv=/path/to/docv2_train_queries.tsv \
  -jar monoforest-0.1.0-SNAPSHOT-runner.jar --index /path/to/index \
  --model /path/to/linear_monomials.json --query "grammar terms medicine"
```

В notebook соответствующая часть `command` выглядит так:

```python
JAVA, f"-Xmx{HEAP}", "-Dmonoforest.queryFrequencyTsv=/path/to/docv2_train_queries.tsv", "-jar", str(JAR),
```

Если указанный внешний файл недоступен, запуск завершается ошибкой, без подмены
его встроенным корпусом. Для моделей с частотными фичами доступность источника
проверяется **до** копирования индекса в RAM. Сам подсчёт частот остаётся в первом
запросе и входит в `search_ms`, как до исправления. Частоты и score не заменяются нулями.
