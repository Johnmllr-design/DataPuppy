from fastapi import FastAPI
import requests


response = requests.get("https://w6tobkqgg357bxm2m2e6lzu6oe0pxzol.lambda-url.us-east-1.on.aws/")
body = response.json()
print(body)