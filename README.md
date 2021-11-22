[![Build Status](https://img.shields.io/endpoint?url=https%3A%2F%2Fstatusbadge-jx.apps.serv.run%2Fentando-k8s%2Fentando-k8s-app-controller)](https://github.com/entando-k8s/devops-results/tree/logs/jenkins-x/logs/entando-k8s/entando-k8s-app-controller/master)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=alert_status)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-app-controller)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=coverage)](https://entando-k8s.github.io/devops-results/entando-k8s-app-controller/master/jacoco/index.html)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=vulnerabilities)](https://entando-k8s.github.io/devops-results/entando-k8s-app-controller/master/dependency-check-report.html)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=code_smells)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-app-controller)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=security_rating)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-app-controller)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-app-controller&metric=sqale_index)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-app-controller)



# Entando Kubernetes App Controller

This project produces the Entando App Controller image. It is a run-to-completion style image
that processes a single EntandoApp custom resource and deploys a the app image and supporting
images as specified.

For more information on how to configure this image, please consult the documentation in the [entando-k8s-operator-common](https://github.com/entando-k8s/entando-k8s-operator-common) project


# Build the artifact

```bash
mvn clean -B test \
    org.jacoco:jacoco-maven-plugin:prepare-agent \
    org.jacoco:jacoco-maven-plugin:report \
    -Ppre-deployment-verification -Ppost-deployment-verification
```

# Build the docker image

```bash
mvn package -Pjvm
docker build . -f Dockerfile.jvm --no-cache -t {{image-full-name}}
```

# Notes

 - The variable `ENTANDO_DEPLOYMENT_PARALLELISM` defines how many deployments to start in parallel. The accepted range is 1-3 and the default value is 3.
