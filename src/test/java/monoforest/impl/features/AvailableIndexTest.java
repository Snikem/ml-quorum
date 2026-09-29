package monoforest.impl.features;

import monoforest.impl.AppConfig;
import org.junit.Before;
import org.junit.Assume;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Без подключенного диска выполняются автономные тесты, индексные отмечаются skipped. */
public abstract class AvailableIndexTest {
    @Before
    public void requireAccessibleIndex() {
        Path path = Paths.get(AppConfig.getIndexDir());
        Assume.assumeTrue("Индекс недоступен: " + path + "; выполняются автономные тесты",
                Files.isDirectory(path) && Files.isReadable(path));
        // Поврежденный доступный индекс и отсутствие полей НЕ являются причиной пропуска.
    }
}
