# NOTE: Keep resource definitions in sync with ../compat-terraform/main.tf

# ── GCS Bucket ────────────────────────────────────────────────────────────────
resource "google_storage_bucket" "compat" {
  name          = "floci-compat-bucket-tofu"
  location      = "US"
  force_destroy = true

  uniform_bucket_level_access = false

  labels = {
    env = "compat-test"
  }
}

resource "google_storage_bucket_object" "readme" {
  bucket       = google_storage_bucket.compat.name
  name         = "README.txt"
  content      = "floci-gcp opentofu compat test"
  content_type = "text/plain"
}

# ── IAM Service Account ───────────────────────────────────────────────────────
resource "google_service_account" "compat" {
  account_id   = "floci-compat-sa-tofu"
  display_name = "floci compat test service account (opentofu)"
}

# ── Secret Manager ────────────────────────────────────────────────────────────
resource "google_secret_manager_secret" "compat" {
  secret_id = "floci-compat-secret-tofu"
  project   = var.project

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "compat" {
  secret      = google_secret_manager_secret.compat.id
  secret_data = "floci-gcp-opentofu-compat-test-secret-value"
}

# ── Cloud Run ────────────────────────────────────────────────────────────────
resource "google_cloud_run_v2_service" "compat" {
  name                = "floci-compat-run-tofu"
  location            = var.region
  deletion_protection = false
  ingress             = "INGRESS_TRAFFIC_ALL"

  labels = {
    env = var.cloud_run_label
  }

  template {
    service_account                  = google_service_account.compat.email
    timeout                          = "30s"
    max_instance_request_concurrency = 8

    scaling {
      min_instance_count = 0
      max_instance_count = 1
    }

    containers {
      image = "nginx:latest"

      ports {
        container_port = 80
      }

      env {
        name  = "FLOCI_COMPAT"
        value = var.cloud_run_env_value
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "128Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }
    }
  }
}

# ── Outputs ───────────────────────────────────────────────────────────────────
output "bucket_name" {
  value = google_storage_bucket.compat.name
}

output "object_name" {
  value = google_storage_bucket_object.readme.name
}

output "service_account_email" {
  value = google_service_account.compat.email
}

output "secret_name" {
  value = google_secret_manager_secret.compat.name
}

output "secret_version_name" {
  value = google_secret_manager_secret_version.compat.name
}

output "cloud_run_service_name" {
  value = google_cloud_run_v2_service.compat.name
}

output "cloud_run_uri" {
  value = google_cloud_run_v2_service.compat.uri
}
