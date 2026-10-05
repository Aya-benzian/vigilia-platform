from fastapi import FastAPI, Response
from prometheus_fastapi_instrumentator import Instrumentator
from prometheus_client import Gauge
import random

app = FastAPI(title="ML Serving API")

# Mock the specific metric required by contract v1.0
uncertainty_metric = Gauge(
    "fintech_ml_prediction_uncertainty", 
    "Uncertainty score of the ML prediction"
)

Instrumentator().instrument(app).expose(app, endpoint="/metrics")

@app.get("/health/live")
def liveness():
    return {"status": "UP"}

@app.get("/health/ready")
def readiness():
    # Simulate loading model
    return {"status": "UP"}

@app.get("/predict")
def predict():
    # Simulate high uncertainty occasionally to test the alert
    uncertainty = random.uniform(0.1, 0.6)
    uncertainty_metric.set(uncertainty)
    return {"prediction": "APPROVED", "uncertainty": uncertainty}
