"""
Vigilia Platform — MLflow Infrastructure Smoke Test for Member 1

This script verifies that the MLflow Tracking Server and Model Registry are:
1. Reachable from the training environment.
2. Capable of logging experiments, parameters, and metrics.
3. Capable of uploading artifacts to the persistent backend via HTTP proxy.
4. Capable of registering a model version and setting the 'champion' alias.
5. Capable of serving artifact downloads back to clients.

This script uses a dummy Scikit-Learn estimator and does NOT alter M1's actual ML models or pipelines.
"""

import os
import sys
import tempfile
import mlflow
from mlflow.tracking import MlflowClient
from sklearn.dummy import DummyClassifier
import numpy as np

def run_smoke_test():
    tracking_uri = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
    experiment_name = os.getenv("MLFLOW_EXPERIMENT_NAME", "vigilia-smoke-test")
    model_name = "smoke_test_champion_model"

    print(f"=== Vigilia MLflow Smoke Test ===")
    print(f"Tracking URI: {tracking_uri}")
    print(f"Experiment:   {experiment_name}")

    mlflow.set_tracking_uri(tracking_uri)
    client = MlflowClient(tracking_uri=tracking_uri)

    # 1. Create or get experiment
    experiment = client.get_experiment_by_name(experiment_name)
    if experiment is None:
        experiment_id = client.create_experiment(experiment_name)
        print(f"[*] Created experiment '{experiment_name}' with ID: {experiment_id}")
    else:
        experiment_id = experiment.experiment_id
        print(f"[*] Found existing experiment '{experiment_name}' with ID: {experiment_id}")

    # 2. Start run and log parameters, metrics, artifact, and disposable model
    with mlflow.start_run(experiment_id=experiment_id, run_name="smoke_test_run") as run:
        run_id = run.info.run_id
        print(f"[*] Active Run ID: {run_id}")

        # Log parameter & metric
        mlflow.log_param("test_framework", "scikit-learn")
        mlflow.log_metric("dummy_accuracy", 0.99)
        print("    - Logged parameter: test_framework=scikit-learn")
        print("    - Logged metric: dummy_accuracy=0.99")

        # Log artifact file
        with tempfile.TemporaryDirectory() as tmp_dir:
            test_file_path = os.path.join(tmp_dir, "test_artifact.txt")
            with open(test_file_path, "w") as f:
                f.write("vigilia-mlflow-artifact-content-ok")
            mlflow.log_artifact(test_file_path, artifact_path="smoke_artifacts")
            print("    - Logged test artifact: smoke_artifacts/test_artifact.txt")

        # Fit and log a minimal dummy model
        X_dummy = np.array([[1.0], [2.0], [3.0]])
        y_dummy = np.array([0, 1, 0])
        clf = DummyClassifier(strategy="most_frequent")
        clf.fit(X_dummy, y_dummy)

        model_info = mlflow.sklearn.log_model(
            sk_model=clf,
            artifact_path="model",
            registered_model_name=model_name
        )
        print(f"    - Logged & registered model '{model_name}'")

    # 3. Verify artifact retrieval through tracking server proxy
    print("[*] Verifying artifact download via HTTP proxy...")
    downloaded_path = client.download_artifacts(run_id, "smoke_artifacts/test_artifact.txt")
    with open(downloaded_path, "r") as f:
        content = f.read().strip()
    assert content == "vigilia-mlflow-artifact-content-ok", f"Artifact content mismatch: {content}"
    print(f"    [+] Successfully downloaded and verified artifact content: '{content}'")

    # 4. Set and verify 'champion' model alias
    print(f"[*] Assigning 'champion' alias to latest version of '{model_name}'...")
    registered_model = client.get_registered_model(model_name)
    latest_version = registered_model.latest_versions[-1].version
    client.set_registered_model_alias(name=model_name, alias="champion", version=latest_version)
    print(f"    - Set alias 'champion' -> Version {latest_version}")

    # Verify resolving champion alias
    model_version = client.get_model_version_by_alias(name=model_name, alias="champion")
    assert model_version.version == latest_version, f"Expected version {latest_version}, got {model_version.version}"
    print(f"    [+] Successfully resolved alias 'champion' -> Model version {model_version.version}")

    # 5. Verify model loading via models:/ URI
    model_uri = f"models:/{model_name}@champion"
    print(f"[*] Verifying model load via URI '{model_uri}'...")
    loaded_model = mlflow.sklearn.load_model(model_uri)
    predictions = loaded_model.predict(X_dummy)
    assert len(predictions) == 3, f"Unexpected predictions length: {len(predictions)}"
    print(f"    [+] Model successfully loaded and evaluated: predictions={predictions.tolist()}")

    print("\n=== ALL MLFLOW SMOKE TESTS PASSED SUCCESSFULLY! ===")
    return True

if __name__ == "__main__":
    try:
        run_smoke_test()
    except Exception as e:
        print(f"\n[!] SMOKE TEST FAILED: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)
