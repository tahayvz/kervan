package com.kervan.inventory.infrastructure.messaging;

/**
 * Bu sürümün tanımadığı bir komut tipi geldi.
 *
 * <p>Beklenen bir durumdur: konuya yeni bir komut tipi eklendiğinde, henüz
 * güncellenmemiş bir kopya onu görebilir (rolling upgrade).
 *
 * <p>Yeniden denenmez — bekleyerek tanınır hâle gelmez. Doğrudan ölü mektup konusuna
 * gider; oradan görülür ve gerekiyorsa yeni sürüm dağıtıldıktan sonra geri konur.
 */
public class UnsupportedCommandException extends RuntimeException {

    UnsupportedCommandException(Object message) {
        super("Tanınmayan komut tipi: " + message.getClass().getName());
    }
}
