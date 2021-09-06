{{- define "bkci.websocket.yaml" -}}
# Websocket Service Template 服务配置文件模板

# 服务器端口配置，在同一台机器上部署多个微服务，端口号要不同 21926
server:
  port: {{ .Values.config.bkCiWebsocketApiPort }}
{{- end -}}