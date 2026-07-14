# Inner loop for the k3d phase: `make tilt` (cluster must exist: make k8s-up)
# Go → docker_build; JVM → jib via local_resource + custom_build pattern.

k8s_yaml(kustomize('infra/k8s/overlays/dev'))

docker_build('agora-registry:5001/link-service', 'services/link-service')

custom_build(
    'agora-registry:5001/catalog-service',
    'mvn -q -f services/catalog-service/pom.xml jib:build -Djib.allowInsecureRegistries=true -Dimage=$EXPECTED_REF',
    deps=['services/catalog-service/src', 'services/catalog-service/pom.xml'],
    skips_local_docker=True,
)

k8s_resource('link-service', port_forwards='18081:8081')
k8s_resource('catalog-service', port_forwards='18083:8083')
