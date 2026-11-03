"""
prepare_transfer_data.py — Downloads and filters PaySim for the TRANSFER domain.

Streams the PaySim dataset directly from the official mirror, filters strictly
to `type == 'TRANSFER'`, drops post-transaction leakage columns, and saves
a clean, lightweight raw transfer file at `data/raw/paysim_transfer.csv`.
"""

import csv
import logging
import sys
import time
import urllib.request
from pathlib import Path

# Configure paths
SRC_DIR = Path(__file__).resolve().parent
ROOT_DIR = SRC_DIR.parent
DATA_RAW_DIR = ROOT_DIR / "data" / "raw"
DATA_RAW_DIR.mkdir(parents=True, exist_ok=True)

TARGET_FILE = DATA_RAW_DIR / "paysim_transfer.csv"

# Direct URL to the official PaySim dataset hosted on Hugging Face
PAYSIM_URL = "https://huggingface.co/datasets/theman10/paysim/resolve/main/paysim.csv"


# Columns to keep (strictly pre-transaction features + identifiers for velocity + target)
# DROPPED: type (constant TRANSFER), newbalanceOrig & newbalanceDest (leakage), isFlaggedFraud (hard rule)
COLUMNS_TO_KEEP = [
    "step",
    "nameOrig",
    "amount",
    "oldbalanceOrg",
    "nameDest",
    "oldbalanceDest",
    "isFraud",
]

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("prepare_transfer")


def download_and_filter_paysim(url: str = PAYSIM_URL, output_path: Path = TARGET_FILE) -> Path:
    if output_path.exists() and output_path.stat().st_size > 10_000_000:
        logger.info(f"Clean PaySim transfer data already exists at {output_path} ({output_path.stat().st_size / (1024*1024):.2f} MB). Skipping download.")
        return output_path

    logger.info(f"Connecting to PaySim stream: {url}")
    logger.info("Streaming and filtering rows on the fly (type == 'TRANSFER')...")

    start_time = time.time()
    total_rows = 0
    transfer_rows = 0
    fraud_rows = 0

    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Vigilia-ML/1.0"}
    )

    with urllib.request.urlopen(req) as response:
        import io
        # Read text stream line by line
        text_stream = io.TextIOWrapper(response, encoding="utf-8")
        reader = csv.reader(text_stream)

        # Read header
        header = next(reader)
        col_idx = {name: i for i, name in enumerate(header)}

        type_idx = col_idx["type"]
        is_fraud_idx = col_idx["isFraud"]

        with open(output_path, "w", newline="", encoding="utf-8") as f_out:
            writer = csv.writer(f_out)
            writer.writerow(COLUMNS_TO_KEEP)

            keep_indices = [col_idx[c] for c in COLUMNS_TO_KEEP]

            for row in reader:
                total_rows += 1
                if row[type_idx] == "TRANSFER":
                    transfer_rows += 1
                    if row[is_fraud_idx] == "1":
                        fraud_rows += 1
                    
                    writer.writerow([row[i] for i in keep_indices])

                if total_rows % 500_000 == 0:
                    elapsed = time.time() - start_time
                    logger.info(
                        f"Processed {total_rows:,} rows... "
                        f"Found {transfer_rows:,} transfers ({fraud_rows:,} frauds) in {elapsed:.1f}s"
                    )

    elapsed = time.time() - start_time
    logger.info(
        f"SUCCESS! Finished processing {total_rows:,} total rows in {elapsed:.1f}s. "
        f"Saved {transfer_rows:,} clean TRANSFER transactions ({fraud_rows:,} frauds, "
        f"fraud rate: {fraud_rows / transfer_rows * 100:.3f}%) to {output_path}"
    )
    return output_path


if __name__ == "__main__":
    download_and_filter_paysim()
