package dev.ailiao.expert;

final class ProductionException extends IllegalArgumentException {
    final int httpStatus;
    final String code;
    ProductionException(int status,String code,String message){super(message);this.httpStatus=status;this.code=code;}
}
