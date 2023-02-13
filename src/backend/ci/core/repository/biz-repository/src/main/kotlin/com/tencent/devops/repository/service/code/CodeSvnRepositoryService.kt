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
package com.tencent.devops.repository.service.code

import com.tencent.devops.common.api.constant.RepositoryMessageCode
import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.model.repository.tables.records.TRepositoryRecord
import com.tencent.devops.repository.dao.RepositoryCodeSvnDao
import com.tencent.devops.repository.dao.RepositoryDao
import com.tencent.devops.repository.pojo.CodeSvnRepository
import com.tencent.devops.repository.pojo.Repository
import com.tencent.devops.repository.pojo.auth.RepoAuthInfo
import com.tencent.devops.repository.pojo.credential.RepoCredentialInfo
import com.tencent.devops.repository.pojo.enums.RepoAuthType
import com.tencent.devops.repository.service.CredentialService
import com.tencent.devops.repository.service.scm.IScmService
import com.tencent.devops.scm.enums.CodeSvnRegion
import com.tencent.devops.scm.pojo.TokenCheckResult
import com.tencent.devops.ticket.pojo.enums.CredentialType
import org.apache.commons.lang3.StringUtils
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class CodeSvnRepositoryService @Autowired constructor(
    private val repositoryDao: RepositoryDao,
    private val repositoryCodeSvnDao: RepositoryCodeSvnDao,
    private val dslContext: DSLContext,
    private val scmService: IScmService,
    private val credentialService: CredentialService
) : CodeRepositoryService<CodeSvnRepository> {
    override fun repositoryType(): String {
        return CodeSvnRepository::class.java.name
    }

    override fun create(projectId: String, userId: String, repository: CodeSvnRepository): Long {
        repository.projectId = projectId
        checkCredentialInfo(repository = repository)
        var repositoryId = 0L
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            repositoryId = repositoryDao.create(
                dslContext = transactionContext,
                projectId = projectId,
                userId = userId,
                aliasName = repository.aliasName,
                url = repository.getFormatURL(),
                type = ScmType.CODE_SVN
            )
            // 如果repository为null，则默认为TC
            repositoryCodeSvnDao.create(
                dslContext = transactionContext,
                repositoryId = repositoryId,
                region = repository.region ?: CodeSvnRegion.TC,
                projectName = repository.projectName,
                userName = repository.userName,
                privateToken = repository.credentialId,
                svnType = repository.svnType
            )
        }
        return repositoryId
    }

    override fun edit(
        userId: String,
        projectId: String,
        repositoryHashId: String,
        repository: CodeSvnRepository,
        record: TRepositoryRecord
    ) {
        // 提交的参数与数据库中类型不匹配
        if (!StringUtils.equals(record.type, ScmType.CODE_SVN.name)) {
            throw OperationException(MessageCodeUtil.getCodeLanMessage(RepositoryMessageCode.SVN_INVALID))
        }
        val repositoryId = HashUtil.decodeOtherIdToLong(repositoryHashId)
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            repositoryDao.edit(
                dslContext = transactionContext,
                repositoryId = repositoryId,
                aliasName = repository.aliasName,
                url = repository.getFormatURL()
            )
            repositoryCodeSvnDao.edit(
                dslContext = transactionContext,
                repositoryId = repositoryId,
                region = repository.region ?: CodeSvnRegion.TC,
                projectName = repository.projectName,
                userName = repository.userName,
                credentialId = repository.credentialId,
                svnType = repository.svnType
            )
        }
    }

    override fun compose(repository: TRepositoryRecord): Repository {
        val record = repositoryCodeSvnDao.get(dslContext, repository.repositoryId)
        return CodeSvnRepository(
            aliasName = repository.aliasName,
            url = repository.url,
            credentialId = record.credentialId,
            region = if (record.region.isNullOrBlank()) {
                CodeSvnRegion.TC
            } else {
                CodeSvnRegion.valueOf(record.region)
            },
            projectName = record.projectName,
            userName = record.userName,
            projectId = repository.projectId,
            repoHashId = HashUtil.encodeOtherLongId(repository.repositoryId),
            svnType = record.svnType
        )
    }

    fun checkToken(
        repoCredentialInfo: RepoCredentialInfo,
        repository: CodeSvnRepository
    ): TokenCheckResult {
        // 根据凭证类型匹配私钥
        val privateKey = when (repoCredentialInfo.credentialInfoType) {
            CredentialType.TOKEN_SSH_PRIVATEKEY.name , CredentialType.SSH_PRIVATEKEY.name -> {
                repoCredentialInfo.privateKey
            }
            CredentialType.TOKEN_USERNAME_PASSWORD.name, CredentialType.USERNAME_PASSWORD.name -> {
                repoCredentialInfo.password
            }
            else -> {
                throw ErrorCodeException(errorCode = RepositoryMessageCode.GET_TICKET_FAIL)
            }
        }

        return scmService.checkPrivateKeyAndToken(
            projectName = repository.projectName,
            url = repository.getFormatURL(),
            type = ScmType.CODE_SVN,
            privateKey = privateKey,
            passPhrase = repoCredentialInfo.passPhrase,
            token = null,
            region = repository.region,
            userName = repository.userName
        )
    }

    /**
     * 检查凭证信息
     */
    private fun checkCredentialInfo(repository: CodeSvnRepository): RepoCredentialInfo {
        // 凭证信息
        val repoCredentialInfo = getCredentialInfo(
            repository = repository
        )
        val checkResult = checkToken(
            repoCredentialInfo = repoCredentialInfo,
            repository = repository
        )
        if (!checkResult.result) {
            logger.warn("Fail to check the repo token & private key because of ${checkResult.message}")
            throw OperationException(checkResult.message)
        }
        return repoCredentialInfo
    }

    override fun getAuthInfo(repositoryIds: List<Long>): Map<Long, RepoAuthInfo> {
        return repositoryCodeSvnDao.list(
            dslContext = dslContext,
            repositoryIds = repositoryIds.toSet()
        ).associateBy({ it.repositoryId }, {
            RepoAuthInfo(
                authType = it.svnType?.toUpperCase() ?: RepoAuthType.SSH.name,
                credentialId = it.credentialId,
                svnType = it.svnType
            )
        })
    }

    /**
     * 获取凭证信息
     */
    fun getCredentialInfo(repository: CodeSvnRepository): RepoCredentialInfo {
        // 凭证信息
        return credentialService.getCredentialInfo(
            projectId = repository.projectId!!,
            repository = repository
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CodeSvnRepositoryService::class.java)
    }
}
