-- 한글이 들어가는 컬럼을 문자 기준 길이로 바꾼다.
--
-- 왜: Oracle 기본 NLS_LENGTH_SEMANTICS 는 BYTE 다. AL32UTF8 에서 한글 한 글자는 3바이트이므로
--     VARCHAR2(140) 은 사실상 한글 46자밖에 담지 못한다. API 계약(@Size(max=140))과 pacs.008
--     스키마(rmtInf maxLength 140)는 '문자' 140 을 약속하는데 DB 만 바이트로 세고 있어서,
--     한글 47자짜리 정상 적요가 ORA-12899 로 500 이 되고 있었다.
--
-- 대상은 사람이 쓴 한글이 들어갈 수 있는 컬럼만이다. msgId·상태코드처럼 ASCII 만 들어가는
-- 컬럼은 바꾸지 않는다 — 인덱스 크기만 늘고 얻는 것이 없다.

ALTER TABLE PAYMENT                MODIFY (REMITTANCE_INFO VARCHAR2(140 CHAR));
ALTER TABLE PAYMENT_STATUS_HISTORY MODIFY (REASON          VARCHAR2(400 CHAR));
ALTER TABLE RECON_BREAK            MODIFY (DETAIL          VARCHAR2(1000 CHAR));
