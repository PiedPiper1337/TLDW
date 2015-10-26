package modules;

import com.google.inject.AbstractModule;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import play.Logger;

/**
 * Created by brianzhao on 10/25/15.
 */

public class InitializerModule extends AbstractModule {
    private static final org.slf4j.Logger logger = Logger.of(InitializerModule.class).underlying();


    @Override
    protected void configure() {
        String osName = System.getProperty("os.name").toLowerCase();
        logger.debug("Detected operating system: {}", osName);
        if (osName.startsWith("mac")) {
            System.setProperty("webdriver.chrome.driver", "chromedriverMac");
        } else if (osName.startsWith("window")) {
            //do nothing for now
        } else {
            System.setProperty("webdriver.chrome.driver", "chromedriverLinux");
        }

        logger.debug("chromedriver environment path set");
        bind(WebDriver.class).to(ChromeDriver.class).asEagerSingleton();
    }
}


