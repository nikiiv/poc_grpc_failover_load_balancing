tasks.register("start_bff") {
    group = "demo"
    description = "Runs the Micronaut BFF (:bff:run; HTTP :8080 + gRPC :7000)."
    dependsOn(":bff:run")
}

tasks.register("start_ui") {
    group = "demo"
    description = "Runs the Vite dev server (:web-client:start_bff_client)."
    dependsOn(":web-client:start_bff_client")
}

fun TaskContainer.registerServer(taskName: String, id: String, port: Int) {
    register<JavaExec>(taskName) {
        group = "demo"
        description = "Runs a backend SERVER_ID=$id on :$port, registering with BFF on 127.0.0.1:7001."
        dependsOn(":grpc-server:classes")
        classpath = project(":grpc-server").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("com.example.poc.server.ServerApp")
        environment("SERVER_ID", id)
        environment("SERVER_PORT", port.toString())
        environment("ADVERTISED_HOST", "127.0.0.1")
        environment("BFF_REGISTRY", "127.0.0.1:7001")
    }
}

tasks.registerServer("start_server_a", "server-a", 9101)
tasks.registerServer("start_server_b", "server-b", 9102)
tasks.registerServer("start_server_c", "server-c", 9103)
