FROM mysql:5.7.26
LABEL OG=felord.cn
RUN mkdir -p /data/sql
COPY ./sql/ /data/sql
RUN find /data/sql -type f -name "*.sql" | sort | xargs -I {} mv {} /docker-entrypoint-initdb.d
