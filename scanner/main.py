from fastapi import FastAPI
from datetime import datetime

app = FastAPI(title="AppVault Scanner", version="0.1.0")

@app.get("/health")
def health():
    return {
        "status": "UP",
        "service": "appvault-scanner",
        "version": "0.1.0",
        "timestamp": datetime.utcnow().isoformat()
    }

#start the server with: uvicorn main:app --host