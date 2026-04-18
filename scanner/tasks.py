import logging
from celery_app import celery_app
from scanner.scan_worker import run_scan

logger = logging.getLogger(__name__)


@celery_app.task(name="tasks.scan_apk", bind=True, max_retries=2)
def scan_apk_task(self, app_id: str, version_id: str,
                  apk_url: str, callback_id: str):
    """Celery task that runs the full APK scan pipeline."""
    try:
        run_scan(
            app_id=app_id,
            version_id=version_id,
            apk_url=apk_url,
            callback_id=callback_id,
        )
    except Exception as exc:
        logger.error("Task failed: %s", exc, exc_info=True)
        raise self.retry(exc=exc, countdown=30)