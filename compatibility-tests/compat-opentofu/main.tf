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

resource "google_storage_bucket_object" "cloud_run_index" {
  bucket       = google_storage_bucket.compat.name
  name         = "index.html"
  content      = "floci-gcp opentofu cloud run gcs volume"
  content_type = "text/html"
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
resource "terraform_data" "cloud_run_replacement" {
  input = var.cloud_run_replace_token
}

resource "google_cloud_run_v2_service" "compat" {
  name                = var.cloud_run_name
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

    volumes {
      name = "site"

      gcs {
        bucket    = google_storage_bucket.compat.name
        read_only = true
      }
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

      volume_mounts {
        name       = "site"
        mount_path = "/usr/share/nginx/html"
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

  lifecycle {
    replace_triggered_by = [
      terraform_data.cloud_run_replacement
    ]
  }

  depends_on = [
    google_storage_bucket_object.cloud_run_index
  ]
}

# ── Cloud SQL for PostgreSQL ─────────────────────────────────────────────────
resource "google_sql_database_instance" "compat" {
  name             = "floci-compat-postgres-tofu"
  project          = var.project
  region           = var.region
  database_version = "POSTGRES_18"

  deletion_protection = false

  settings {
    tier = "db-custom-1-3840"
  }
}

resource "google_sql_database" "compat" {
  name     = "appdb"
  project  = var.project
  instance = google_sql_database_instance.compat.name
}

resource "google_sql_user" "compat" {
  name     = "app"
  project  = var.project
  instance = google_sql_database_instance.compat.name
  password = "floci-compat-password"
}

# ── Cloud KMS ─────────────────────────────────────────────────────────────────
resource "google_kms_key_ring" "compat" {
  name     = "floci-compat-keyring-tofu"
  location = var.region
  project  = var.project
}

resource "google_kms_crypto_key" "compat" {
  name     = "floci-compat-key-tofu"
  key_ring = google_kms_key_ring.compat.id
  purpose  = "ENCRYPT_DECRYPT"
}

# ── Outputs ───────────────────────────────────────────────────────────────────
output "key_ring_name" {
  value = google_kms_key_ring.compat.name
}

output "crypto_key_name" {
  value = google_kms_crypto_key.compat.name
}

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

output "sql_instance_name" {
  value = google_sql_database_instance.compat.name
}

output "sql_database_name" {
  value = google_sql_database.compat.name
}

output "sql_user_name" {
  value = google_sql_user.compat.name
}

# ── Service Usage ─────────────────────────────────────────────────────────────
resource "google_project_service" "run_api" {
  project = var.project
  service = "run.googleapis.com"

  disable_on_destroy = true
}

output "enabled_service" {
  value = google_project_service.run_api.service
}
