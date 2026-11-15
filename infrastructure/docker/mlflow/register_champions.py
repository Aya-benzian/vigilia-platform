import os
import sys
import pickle
import json
from pathlib import Path
import mlflow
import mlflow.sklearn
from mlflow import MlflowClient

MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
ARTIFACTS_DIR = Path(os.getenv("ARTIFACTS_DIR", "/artifacts"))

PURCHASE_MODEL_NAME = "purchase-fraud-model"
TRANSFER_MODEL_NAME = "transfer-fraud-model"
LEGACY_MODEL_NAME   = "fraud-model"
CHAMPION_ALIAS      = "champion"

PURCHASE_PKL = ARTIFACTS_DIR / "purchase_champion_pipeline.pkl"
TRANSFER_PKL = ARTIFACTS_DIR / "transfer_champion_pipeline.pkl"

def load_pkl(path):
    if not path.exists():
        print(f"Warning: {path} does not exist!")
        return None
    with open(path, "rb") as f:
        return pickle.load(f)

def register(pipeline, model_name):
    if pipeline is None:
        return
    print(f"Registering '{model_name}'...")
    with mlflow.start_run(run_name=f"register_{model_name}") as run:
        model_info = mlflow.sklearn.log_model(
            sk_model=pipeline,
            artifact_path="model",
            registered_model_name=model_name,
            serialization_format="cloudpickle"
        )
    client = MlflowClient()
    reg = client.get_registered_model(model_name)
    ver = reg.latest_versions[-1].version
    client.set_registered_model_alias(name=model_name, alias=CHAMPION_ALIAS, version=ver)
    print(f"Set '{model_name}' alias '@{CHAMPION_ALIAS}' -> version {ver}")

def main():
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
    print(f"Connected to MLflow: {MLFLOW_TRACKING_URI}")
    
    p_pipe = load_pkl(PURCHASE_PKL)
    t_pipe = load_pkl(TRANSFER_PKL)
    
    register(p_pipe, PURCHASE_MODEL_NAME)
    register(t_pipe, TRANSFER_MODEL_NAME)
    register(p_pipe, LEGACY_MODEL_NAME)
    print("Registration completed successfully!")

if __name__ == "__main__":
    main()
