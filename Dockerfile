FROM nginx:alpine

# Use Aliyun Alpine mirror for China
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories

# Install Python + Flask
RUN apk add --no-cache python3 py3-flask py3-certifi py3-markupsafe

RUN ln -sf /usr/bin/python3 /usr/bin/python

COPY sms_server.py /app/sms_server.py
COPY index.html /usr/share/nginx/html/index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY start.sh /start.sh

RUN chmod +x /start.sh && \
    chmod 644 /usr/share/nginx/html/index.html && \
    mkdir -p /app/data

EXPOSE 8989
VOLUME ["/app/data"]

CMD ["/start.sh"]
