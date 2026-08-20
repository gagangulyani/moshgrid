use std::sync::Mutex;
use vodozemac::olm::{Account, AccountPickle};

uniffi::include_scaffolding!("moshgrid_crypto");

#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("failed to unpickle account")]
    Unpickle,
    #[error("pickle key must be exactly 32 bytes")]
    InvalidKey,
}

pub struct IdentityKeys {
    pub ed25519: String,
    pub curve25519: String,
}

pub struct MoshgridAccount {
    inner: Mutex<Account>,
}

fn to_key_array(key: Vec<u8>) -> Result<[u8; 32], CryptoError> {
    key.try_into().map_err(|_| CryptoError::InvalidKey)
}

impl MoshgridAccount {
    pub fn new() -> Self {
        Self { inner: Mutex::new(Account::new()) }
    }

    pub fn unpickle(pickle: String, key: Vec<u8>) -> Result<Self, CryptoError> {
        let key = to_key_array(key)?;
        let pickle = AccountPickle::from_encrypted(&pickle, &key)
            .map_err(|_| CryptoError::Unpickle)?;
        Ok(Self { inner: Mutex::new(Account::from_pickle(pickle)) })
    }

    pub fn identity_keys(&self) -> IdentityKeys {
        let account = self.inner.lock().unwrap();
        let keys = account.identity_keys();
        IdentityKeys {
            ed25519: keys.ed25519.to_base64(),
            curve25519: keys.curve25519.to_base64(),
        }
    }

    pub fn one_time_keys(&self, count: u32) -> Vec<String> {
        let mut account = self.inner.lock().unwrap();
        account.generate_one_time_keys(count as usize);
        account.one_time_keys().values().map(|k| k.to_base64()).collect()
    }

    /// Encrypted, never plain. `AccountPickle` serialises the Ed25519 and
    /// Curve25519 SECRET keys, so a plain-JSON pickle writes the device's
    /// private keys to disk in the clear.
    pub fn pickle(&self, key: Vec<u8>) -> Result<String, CryptoError> {
        let key = to_key_array(key)?;
        Ok(self.inner.lock().unwrap().pickle().encrypt(&key))
    }

    pub fn sign(&self, message: Vec<u8>) -> String {
        self.inner.lock().unwrap().sign(&message).to_base64()
    }
}

impl Default for MoshgridAccount {
    fn default() -> Self {
        Self::new()
    }
}
