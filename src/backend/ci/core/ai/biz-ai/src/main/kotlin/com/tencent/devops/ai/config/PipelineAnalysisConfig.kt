package com.tencent.devops.ai.config

import com.tencent.devops.ai.properties.PipelineAnalysisProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PipelineAnalysisProperties::class)
class PipelineAnalysisConfig
