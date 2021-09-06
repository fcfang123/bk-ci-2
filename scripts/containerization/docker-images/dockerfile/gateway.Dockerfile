#TODO : 后续可以维护统一个openresty
FROM bkrepo/openrestry:0.0.1

LABEL maintainer="Tencent BlueKing Devops"

ENV INSTALL_PATH=/data/workspace/

COPY ./ci/gateway /data/workspace/gateway
COPY ./ci/support-files/templates /data/workspace/templates
COPY ./ci/scripts /data/workspace/scripts
COPY ./ci/frontend /data/workspace/frontend

RUN ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo 'Asia/Shanghai' > /etc/timezone && \
    rm -rf /usr/local/openresty/nginx/conf &&\
    ln -s  /data/workspace/gateway/core /usr/local/openresty/nginx/conf &&\
    chmod +x /data/workspace/scripts/render_tpl &&\
    mkdir -p /usr/local/openresty/nginx/run/

WORKDIR /usr/local/openresty/nginx/

CMD mkdir -p ${BK_CI_LOGS_DIR}/nginx/ ${BK_CI_HOME} &&\
    ln -s /data/workspace/frontend ${BK_CI_HOME}/frontend &&\
    cp -r /data/workspace/scripts/render_tpl /usr/local/openresty/nginx/ &&\
    touch /usr/local/openresty/nginx/bkenv.properties &&\
    ./render_tpl -m . /data/workspace/templates/gateway* &&\
    ./render_tpl -m . /data/workspace/templates/frontend* &&\
    sbin/nginx -g 'daemon off;'
