FROM postgres:18.6-alpine3.24

RUN install -d -o 70 -g 70 -m 0700 /run/idp
COPY bootstrap-owner.sh set-role-passwords.sh /usr/local/lib/idp/
USER 70:70

# ECS runs the task with a read-only root, and fills a volume at this path from the image.
ENV TMPDIR=/run/idp
VOLUME /run/idp

ENTRYPOINT ["sh"]
