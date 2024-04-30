/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.multijar

import com.tencent.devops.common.service.MicroService
import com.tencent.devops.common.service.MicroServiceApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.testcontainers.containers.FixedHostPortGenericContainer

@MicroService
@ComponentScan(
    "com.tencent.devops",
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["com\\.tencent\\.devops\\.common\\..*"]
        )
    ]
)
class MultijarApplication

fun main(args: Array<String>) {
    val redis = FixedHostPortGenericContainer("redis:5.0.3-alpine")
        .withFixedExposedPort(30002, 6379)
    redis.start()
    // 创建Elasticsearch容器
    val elasticsearchContainer = FixedHostPortGenericContainer(
        "docker.elastic.co/elasticsearch/elasticsearch:7.14.0"
    ).withFixedExposedPort(30014, 9200)
        .withFixedExposedPort(30015, 9300)
        .withEnv("discovery.type", "single-node")
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ELASTIC_PASSWORD", "blueking")
    // 启动容器
    elasticsearchContainer.start()
    val influxDBContainer = FixedHostPortGenericContainer(
        "docker.io/bitnami/influxdb:1.8.3-debian-10-r88"
    ).withFixedExposedPort(30006, 8086)
        .withEnv("INFLUXDB_ADMIN_USER_PASSWORD", "blueking")
    influxDBContainer.start()

    val rabbitmq = FixedHostPortGenericContainer("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
        .withFixedExposedPort(30003, 5672)
        .withFixedExposedPort(15672, 15672)
        .withEnv("RABBITMQ_DEFAULT_USER", "admin")
        .withEnv("RABBITMQ_DEFAULT_PASS", "blueking")
    rabbitmq.start()

    MicroServiceApplication.run(MultijarApplication::class, args)
}
