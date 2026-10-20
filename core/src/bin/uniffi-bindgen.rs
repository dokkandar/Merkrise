// Bindgen entry point — generates foreign-language bindings from the built
// library, using the SAME uniffi version as the crate (avoids checksum skew).
fn main() {
    uniffi::uniffi_bindgen_main()
}
