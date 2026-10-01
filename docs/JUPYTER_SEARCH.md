# Один запрос из JupyterHub

Нужны Linux, Java 11+ и три загруженных на сервер объекта:
`monoforest-0.1.0-SNAPSHOT-runner.jar`, папка готового Lucene-индекса
и `linear_monomials.json`. Maven на сервере не нужен.
Готовый notebook: `notebooks/one_query.ipynb`.

Команда (также работает в Terminal JupyterHub):

```bash
java -Xmx8g -jar monoforest-0.1.0-SNAPSHOT-runner.jar \
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
проверяются свободное место tmpfs, MemAvailable и видимые ограничения cgroup;
оставляется место под Java heap (`-Xmx8g`) и резерв 12 GiB (`--reserve-gib`).
Резерв — оценка, не гарантия при параллельной нагрузке. Если `/dev/shm` мал,
нужен другой доступный tmpfs нужного размера или изменение лимита администратором.
JAR сам ничего не монтирует и не меняет лимиты сервера.

[Linux tmpfs](https://www.kernel.org/doc/html/latest/filesystems/tmpfs.html)
может использовать swap. Для строго RAM-only эксперимента нужен tmpfs без swap
(опция `noswap`, если поддерживается сервером) или сервер без активного swap.
`-Xmx8g` относится к Java heap: сам индекс хранится в tmpfs, вне heap.

Сборка JAR на машине разработчика:

```bash
mvn -Prunner package
```

Результат: `target/monoforest-0.1.0-SNAPSHOT-runner.jar`.
