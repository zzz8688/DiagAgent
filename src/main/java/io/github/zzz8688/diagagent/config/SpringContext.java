package io.github.zzz8688.diagagent.config;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class SpringContext implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContext.applicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> beanType) {
        if (applicationContext == null) {
            throw new IllegalStateException("Spring ApplicationContext 尚未初始化");
        }
        return applicationContext.getBean(beanType);
    }
}
