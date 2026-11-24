// Arayüzde gösterilen Türkçe etiketler.

export const STRIDE_LABEL: Record<string, string> = {
  SPOOFING: 'Kimlik sahteciliği (S)',
  TAMPERING: 'Veri değiştirme (T)',
  REPUDIATION: 'İnkâr (R)',
  INFORMATION_DISCLOSURE: 'Bilgi ifşası (I)',
  DENIAL_OF_SERVICE: 'Hizmet engelleme (D)',
  ELEVATION_OF_PRIVILEGE: 'Yetki yükseltme (E)',
};

export const STRIDE_SHORT: Record<string, string> = {
  SPOOFING: 'S', TAMPERING: 'T', REPUDIATION: 'R', INFORMATION_DISCLOSURE: 'I', DENIAL_OF_SERVICE: 'D', ELEVATION_OF_PRIVILEGE: 'E',
};

export const LEVEL_LABEL: Record<string, string> = { LOW: 'Düşük', MEDIUM: 'Orta', HIGH: 'Yüksek', CRITICAL: 'Kritik' };

export const DECISION_LABEL: Record<string, string> = {
  PENDING: 'Karar bekliyor', ACCEPTED: 'Kabul edildi', REJECTED: 'Reddedildi', FALSE_POSITIVE: 'Yanlış pozitif',
};

export const STATUS_LABEL: Record<string, string> = {
  IN_PROGRESS: 'İşleniyor',
  WAITING_MODEL_APPROVAL: 'Model onayı bekliyor',
  WAITING_LLM: 'Tarayıcı LLM bekliyor',
  IN_EXPERT_REVIEW: 'Uzman incelemesinde',
  COMPLETED: 'Tamamlandı',
  FAILED: 'Hata',
};

export const STEP_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Bekliyor', RUNNING: 'Çalışıyor', WAITING_USER: 'Analist bekleniyor', WAITING_LLM: 'LLM bekleniyor', DONE: 'Tamamlandı', FAILED: 'Hata',
};

export const COMPONENT_TYPE_LABEL: Record<string, string> = {
  USER: 'Kullanıcı', EXTERNAL_SYSTEM: 'Dış sistem', THIRD_PARTY_SYSTEM: 'Üçüncü taraf sistem', WEB_APP: 'Web uygulaması',
  MOBILE_APP: 'Mobil uygulama', API_GATEWAY: 'API gateway', API: 'API', SERVICE: 'Servis', BATCH_JOB: 'Toplu iş',
  DATABASE: 'Veritabanı', FILE_STORAGE: 'Dosya deposu', MESSAGE_QUEUE: 'Mesaj kuyruğu', CORE_BANKING: 'Çekirdek bankacılık',
  IDENTITY_PROVIDER: 'Kimlik sağlayıcı', OTHER: 'Diğer',
};

export const ZONE_LABEL: Record<string, string> = {
  INTERNET: 'İnternet', DMZ: 'DMZ', INTERNAL: 'İç ağ', THIRD_PARTY: 'Üçüncü taraf', UNKNOWN: 'Bilinmiyor',
};

export const AUTH_LABEL: Record<string, string> = {
  NONE: 'Yok', JWT: 'JWT', OAUTH2: 'OAuth 2.0', MTLS: 'mTLS', SSO: 'Kurumsal SSO', API_KEY: 'API anahtarı',
  BASIC: 'Kullanıcı adı/parola', SERVICE_ACCOUNT: 'Servis hesabı', UNKNOWN: 'Bilinmiyor',
};

export const DATA_CLASS_LABEL: Record<string, string> = {
  PUBLIC: 'Açık', INTERNAL: 'Kurum içi', PII: 'Kişisel veri', CUSTOMER: 'Müşteri', FINANCIAL: 'Finansal', CARD: 'Kart',
  CREDENTIAL: 'Kimlik bilgisi',
};

export const SOURCE_LABEL: Record<string, string> = {
  STRIDE: 'STRIDE', OWASP_TOP10_2021: 'OWASP Top 10', OWASP_API_TOP10_2023: 'OWASP API Top 10', MITRE_ATTACK: 'MITRE ATT&CK',
  BANK_CONTROL: 'Banka kontrol kataloğu', PAST_REVIEW: 'Geçmiş inceleme',
};

export const PROJECT_TYPE_LABEL: Record<string, string> = {
  NEW_APPLICATION: 'Yeni uygulama', INTEGRATION: 'Entegrasyon', INFRASTRUCTURE: 'Altyapı değişikliği', THIRD_PARTY: 'Üçüncü taraf ürün',
};

export function levelClass(level?: string) {
  switch (level) {
    case 'CRITICAL': return 'pill red';
    case 'HIGH': return 'pill orange';
    case 'MEDIUM': return 'pill yellow';
    case 'LOW': return 'pill gray';
    default: return 'pill gray';
  }
}

export function decisionClass(d: string) {
  switch (d) {
    case 'ACCEPTED': return 'pill green';
    case 'REJECTED': return 'pill gray';
    case 'FALSE_POSITIVE': return 'pill gray';
    default: return 'pill blue';
  }
}

export function fmtDate(s?: string) {
  if (!s) return '';
  return new Date(s).toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'short' });
}
