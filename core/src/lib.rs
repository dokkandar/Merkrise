uniffi::setup_scaffolding!();

/// Version banner returned across the uniffi bridge — the Milestone-1 proof
/// that the Rust core is reachable from Kotlin.
#[uniffi::export]
pub fn core_version() -> String {
    "Merkrise core 0.1.0".into()
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_banner_is_stable() {
        assert_eq!(super::core_version(), "Merkrise core 0.1.0");
    }
}
