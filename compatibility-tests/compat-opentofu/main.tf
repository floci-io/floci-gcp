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
