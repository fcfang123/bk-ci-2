package utils

import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Paths

object MysqlContainerInit {
    fun initMysqlContainer() {
        val path = Paths.get("C:\\Users\\bk-ci-2\\support-files\\Dockerfile")
        val mysqlContainer = GenericContainer<GenericContainer<*>>(
            ImageFromDockerfile().withDockerfile(path)
        ).withExposedPorts(3306)
            .withEnv("MYSQL_ROOT_PASSWORD", "blueking")
        mysqlContainer.start()
    }
}
