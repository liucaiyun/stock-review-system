package com.yan.stockreview.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 执行器。本机没有调度中心时不要开：{@code xxl.job.enabled=false}（默认）。
 *
 * <p>真正接上 xxl-job-admin 后再设 {@code xxl.job.enabled=true}，
 * 执行器 appname = {@code stock-review}，JobHandler = {@code WatchPriceRefreshJob}。
 */
@Configuration
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class XxlJobConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobConfig.class);

    @Value("${xxl.job.admin.addresses:}")
    private String adminAddresses;

    @Value("${xxl.job.admin.accessToken:}")
    private String accessToken;

    @Value("${xxl.job.executor.appname:stock-review}")
    private String appname;

    @Value("${xxl.job.executor.address:}")
    private String address;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port:9999}")
    private int port;

    @Value("${xxl.job.executor.logpath:./data/xxl-job-logs}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays:7}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        if (adminAddresses == null || adminAddresses.isBlank()) {
            throw new IllegalStateException("xxl.job.enabled=true 但未配置 xxl.job.admin.addresses");
        }
        log.info("启动 XXL-Job 执行器：appname={}, admin={}, executor-port={}", appname, adminAddresses, port);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
