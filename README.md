
[Front-end](https://github.com/ravel57/itdesk-front)

Запуск Docker:
-

```
sudo docker build --no-cache -t itdesk . && sudo docker run --name itdesk -d --restart unless-stopped -p 443:443 --env bot_token=[BOT-TOKEN] itdesk
```

Запуск Docker-compose:
-
```
sudo docker-compose build && sudo docker-compose up -d 
```