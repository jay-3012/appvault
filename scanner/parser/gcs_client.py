import os
import tempfile
import logging
from google.cloud import storage

logger = logging.getLogger(__name__)


def download_apk_from_gcs(gcs_url: str) -> str:
    """
    Downloads an APK from a GCS URL to a local temp file.
    Returns the local file path.
    gcs_url format: gs://bucket-name/path/to/file.apk
    """
    if not gcs_url.startswith("gs://"):
        raise ValueError(f"Invalid GCS URL: {gcs_url}")

    # Parse bucket and object path
    without_prefix = gcs_url[5:]
    slash_index = without_prefix.index("/")
    bucket_name = without_prefix[:slash_index]
    object_path = without_prefix[slash_index + 1:]

    logger.info("Downloading APK from GCS: %s", gcs_url)

    client = storage.Client()
    bucket = client.bucket(bucket_name)
    blob = bucket.blob(object_path)

    # Write to a named temp file that persists until we delete it
    tmp = tempfile.NamedTemporaryFile(
        suffix=".apk",
        delete=False,
        prefix="appvault_scan_"
    )
    blob.download_to_file(tmp)
    tmp.flush()
    tmp.close()

    logger.info("APK downloaded to: %s", tmp.name)
    return tmp.name


def cleanup_apk(file_path: str) -> None:
    """Deletes the temp APK file after scanning."""
    try:
        os.unlink(file_path)
        logger.info("Cleaned up temp file: %s", file_path)
    except OSError as e:
        logger.warning("Could not delete temp file %s: %s", file_path, e)
