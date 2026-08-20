use moshgrid_crypto::MoshgridAccount;

#[test]
fn account_exposes_both_identity_keys() {
    let account = MoshgridAccount::new();
    let keys = account.identity_keys();
    assert_eq!(keys.ed25519.len(), 43, "base64 Ed25519 key is 43 chars");
    assert_eq!(keys.curve25519.len(), 43, "base64 Curve25519 key is 43 chars");
}

#[test]
fn account_survives_pickle_round_trip() {
    let key = [7u8; 32];
    let account = MoshgridAccount::new();
    let before = account.identity_keys();

    let pickle = account.pickle(key.to_vec()).expect("pickle");
    let restored = MoshgridAccount::unpickle(pickle, key.to_vec()).expect("unpickle");

    assert_eq!(restored.identity_keys().ed25519, before.ed25519);
}

#[test]
fn unpickle_with_wrong_key_fails() {
    let account = MoshgridAccount::new();
    let pickle = account.pickle([1u8; 32].to_vec()).expect("pickle");
    assert!(MoshgridAccount::unpickle(pickle, [2u8; 32].to_vec()).is_err());
}
