# NOTE: Keep resource definitions in sync with ../compat-opentofu/main.tf

# ── GCS Bucket ────────────────────────────────────────────────────────────────
resource "google_storage_bucket" "compat" {
  name          = "floci-compat-bucket"
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
  content      = "floci-gcp terraform compat test"
  content_type = "text/plain"
}

# ── IAM Service Account ───────────────────────────────────────────────────────
resource "google_service_account" "compat" {
  account_id   = "floci-compat-sa"
  display_name = "floci compat test service account"
}

# ── Secret Manager ────────────────────────────────────────────────────────────
resource "google_secret_manager_secret" "compat" {
  secret_id = "floci-compat-secret"
  project   = var.project

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "compat" {
  secret      = google_secret_manager_secret.compat.id
  secret_data = "floci-gcp-compat-test-secret-value"
}

# ── Cloud SQL for PostgreSQL ─────────────────────────────────────────────────
resource "google_sql_database_instance" "compat" {
  name             = "floci-compat-postgres"
  project          = var.project
  region           = var.region
  database_version = "POSTGRES_15"

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
  name     = "floci-compat-keyring"
  location = var.region
  project  = var.project
}

resource "google_kms_crypto_key" "compat" {
  name     = "floci-compat-key"
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

output "sql_instance_name" {
  value = google_sql_database_instance.compat.name
}

output "sql_database_name" {
  value = google_sql_database.compat.name
}

output "sql_user_name" {
  value = google_sql_user.compat.name
}
