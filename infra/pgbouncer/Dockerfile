FROM edoburu/pgbouncer:v1.26.1-p0

USER root
RUN install -d -o 70 -g 70 -m 0700 /run/idp
COPY entrypoint.sh /usr/local/lib/idp/entrypoint.sh
USER 70:70

# ECS runs the task with a read-only root, and fills a volume at this path from the image.
VOLUME /run/idp

ENTRYPOINT ["sh", "/usr/local/lib/idp/entrypoint.sh"]
