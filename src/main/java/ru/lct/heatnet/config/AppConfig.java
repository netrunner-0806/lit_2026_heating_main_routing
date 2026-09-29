package ru.lct.heatnet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AppConfig {

    @Bean(name = "jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor(HeatnetProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(1, props.getWorkers()));
        ex.setMaxPoolSize(Math.max(1, props.getWorkers()));
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("heatnet-job-");
        ex.initialize();
        return ex;
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("Сервис моделирования трасс подключения к тепловым сетям")
                .description("ЛЦТ 2026. Загрузка GeoJSON (WGS84), автоматическое построение вариантов новой тепловой сети "
                        + "по правилам актуального технического приложения, расчёт расходов, ДУ, стоимости, ранжирование, "
                        + "выгрузка результата GeoJSON и независимая валидация.")
                .version("1.0.0"));
    }
}
