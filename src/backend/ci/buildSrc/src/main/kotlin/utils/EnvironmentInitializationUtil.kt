package utils

import com.github.dockerjava.core.DockerClientBuilder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.lang.reflect.Method
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

object EnvironmentInitializationUtil {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", true)
    private const val windowsDockerHost = "tcp://localhost:2375"
    private const val linuxOrMacDockerHost = "unix:///var/run/docker.sock"
    private val mysqlContainerInitialized = AtomicBoolean(false)
    fun initialEnvironment(mysqlInitDockerFileDir: String) {
        synchronized(this) {
            if (mysqlContainerInitialized.get()) return
            clearBeforeExecution()
            initMysql(mysqlInitDockerFileDir)
            startContainer(DockerImageInfo.REDIS)
            startContainer(DockerImageInfo.ELASTICSEARCH)
            startContainer(DockerImageInfo.INFLUXDB)
            startContainer(DockerImageInfo.RABBITMQ)
            Thread.sleep(40000L)
            mysqlContainerInitialized.set(true)
        }
    }

    private fun clearBeforeExecution() {
        val dockerHost = if (isWindows) {
            windowsDockerHost
        } else {
            linuxOrMacDockerHost
        }
        val dockerClient = DockerClientBuilder.getInstance(dockerHost).build()
        val containers = dockerClient.listContainersCmd().exec()
        containers.forEach { container ->
            val publicPorts = container.getPorts().map { it.publicPort }
            if (publicPorts.intersect(DockerImageInfo.getHostPortList().toSet()).isNotEmpty()) {
                dockerClient.stopContainerCmd(container.id).exec()
            }
        }
    }

    private fun initMysql(mysqlInitDockerFileDir: String) {
        val path = Paths.get(mysqlInitDockerFileDir)
        val mysqlContainer = GenericContainer<GenericContainer<*>>(
            ImageFromDockerfile().withDockerfile(path)
        ).withEnv(DockerImageInfo.MYSQL.env)
            .withReuse(true)
        addFixedExposedPort(mysqlContainer, DockerImageInfo.MYSQL.hostPort, DockerImageInfo.MYSQL.containerPort)
        mysqlContainer.start()
    }

    private fun startContainer(imageInfo: DockerImageInfo) {
        val container = GenericContainer<GenericContainer<*>>(imageInfo.dockerImageName)
            .withEnv(imageInfo.env)
            .withReuse(true)
        addFixedExposedPort(container, imageInfo.hostPort, imageInfo.containerPort)
        container.start()
    }

    private fun addFixedExposedPort(
        container: GenericContainer<*>,
        hostPort: Int,
        containerPort: Int
    ) {
        val method: Method = GenericContainer::class.java.getDeclaredMethod(
            "addFixedExposedPort",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(container, hostPort, containerPort)
    }
}
