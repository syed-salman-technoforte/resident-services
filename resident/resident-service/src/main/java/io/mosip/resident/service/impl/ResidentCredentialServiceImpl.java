package io.mosip.resident.service.impl;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang.RandomStringUtils;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.resident.config.LoggerConfiguration;
import io.mosip.resident.constant.ApiName;
import io.mosip.resident.constant.IdType;
import io.mosip.resident.constant.LoggerFileConstant;
import io.mosip.resident.constant.NotificationTemplateCode;
import io.mosip.resident.constant.ResidentErrorCode;
import io.mosip.resident.dto.CredentialCancelRequestResponseDto;
import io.mosip.resident.dto.CredentialReqestDto;
import io.mosip.resident.dto.CredentialRequestStatusDto;
import io.mosip.resident.dto.CredentialRequestStatusResponseDto;
import io.mosip.resident.dto.CredentialTypeResponse;
import io.mosip.resident.dto.CryptomanagerRequestDto;
import io.mosip.resident.dto.CryptomanagerResponseDto;
import io.mosip.resident.dto.DigitalCardStatusResponseDto;
import io.mosip.resident.dto.NotificationRequestDto;
import io.mosip.resident.dto.NotificationResponseDTO;
import io.mosip.resident.dto.PartnerCredentialTypePolicyDto;
import io.mosip.resident.dto.PartnerResponseDto;
import io.mosip.resident.dto.RIDDigitalCardRequestDto;
import io.mosip.resident.dto.RequestWrapper;
import io.mosip.resident.dto.ResidentCredentialRequestDto;
import io.mosip.resident.dto.ResidentCredentialResponseDto;
import io.mosip.resident.dto.ResponseWrapper;
import io.mosip.resident.exception.ApisResourceAccessException;
import io.mosip.resident.exception.DataNotFoundException;
import io.mosip.resident.exception.OtpValidationFailedException;
import io.mosip.resident.exception.ResidentCredentialServiceException;
import io.mosip.resident.exception.ResidentServiceCheckedException;
import io.mosip.resident.exception.ResidentServiceException;
import io.mosip.resident.service.IdAuthService;
import io.mosip.resident.service.NotificationService;
import io.mosip.resident.service.ResidentCredentialService;
import io.mosip.resident.util.AuditUtil;
import io.mosip.resident.util.EventEnum;
import io.mosip.resident.util.JsonUtil;
import io.mosip.resident.util.ResidentServiceRestClient;
import io.mosip.resident.util.TokenGenerator;
import io.mosip.resident.util.Utilitiy;

@Service
public class ResidentCredentialServiceImpl implements ResidentCredentialService {

	private static final String AVAILABLE = "AVAILABLE";

	@Autowired
	IdAuthService idAuthService;

	@Value("${crypto.PrependThumbprint.enable:true}")
	private boolean isPrependThumbprintEnabled;
	
	@Value("${PARTNER_REFERENCE_Id}")
	private String partnerReferenceId;

	@Value("${APPLICATION_Id}")
	private String applicationId;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private AuditUtil audit;

	private static final Logger logger = LoggerConfiguration.logConfig(ResidentCredentialServiceImpl.class);
	
	@Autowired
	private ResidentServiceRestClient residentServiceRestClient;
	
	@Autowired
	private TokenGenerator tokenGenerator;

	@Autowired
	Environment env;

	@Autowired
	NotificationService notificationService;

	@Autowired
	private Utilitiy utilitiy;
	
	@Override
	public ResidentCredentialResponseDto reqCredential(ResidentCredentialRequestDto dto)
			throws ResidentServiceCheckedException {
		ResidentCredentialResponseDto residentCredentialResponseDto=new ResidentCredentialResponseDto();
		RequestWrapper<CredentialReqestDto> requestDto = new RequestWrapper<>();
		ResponseWrapper<PartnerResponseDto> parResponseDto = new ResponseWrapper<PartnerResponseDto>();
		PartnerResponseDto partnerResponseDto = new PartnerResponseDto();
		CredentialReqestDto credentialReqestDto=new CredentialReqestDto();
		Map<String, Object> additionalAttributes = new HashedMap();
		String partnerUrl = env.getProperty(ApiName.PARTNER_API_URL.name()) + "/" + dto.getIssuer();
		URI partnerUri = URI.create(partnerUrl);
		try {

			if (idAuthService.validateOtp(dto.getTransactionID(), dto.getIndividualId(), dto.getOtp())) {

				    credentialReqestDto=prepareCredentialRequest(dto);
					requestDto.setId("mosip.credential.request.service.id");
					requestDto.setRequest(credentialReqestDto);
					requestDto.setRequesttime(DateUtils.formatToISOString(LocalDateTime.now()));
					requestDto.setVersion("1.0");
					parResponseDto = residentServiceRestClient.getApi(partnerUri, ResponseWrapper.class,
							tokenGenerator.getToken());
					partnerResponseDto = JsonUtil.readValue(JsonUtil.writeValueAsString(parResponseDto.getResponse()),
							PartnerResponseDto.class);
					additionalAttributes.put("partnerName",
							partnerResponseDto.getOrganizationName());
					additionalAttributes.put("encryptionKey", credentialReqestDto.getEncryptionKey());
					additionalAttributes.put("credentialName", credentialReqestDto.getCredentialType());

					ResponseWrapper<ResidentCredentialResponseDto> responseDto = residentServiceRestClient.postApi(
							env.getProperty(ApiName.CREDENTIAL_REQ_URL.name()), MediaType.APPLICATION_JSON, requestDto,
							ResponseWrapper.class,
							tokenGenerator.getToken());
					residentCredentialResponseDto = JsonUtil.readValue(
							JsonUtil.writeValueAsString(responseDto.getResponse()),
							ResidentCredentialResponseDto.class);
					additionalAttributes.put("RID", residentCredentialResponseDto.getRequestId());
					sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_SUCCESS,
							additionalAttributes);

				} else {
					logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
							LoggerFileConstant.APPLICATIONID.toString(),
							ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
					sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_FAILURE,
							additionalAttributes);
					audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_EXCEPTION);
					throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
							ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
				}

			} catch (OtpValidationFailedException e) {
				audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_EXCEPTION);
				sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_FAILURE,
						additionalAttributes);
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
						e.getErrorText(), e);
			}

		catch (ResidentServiceCheckedException e) {

			sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_FAILURE, additionalAttributes);
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		catch (ApisResourceAccessException e) {
			sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_FAILURE, additionalAttributes);
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		catch (IOException e) {
			sendNotification(dto.getIndividualId(), NotificationTemplateCode.RS_CRE_REQ_FAILURE, additionalAttributes);
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		}

		return residentCredentialResponseDto;
	}
	
	@Override
	public byte[] getCard(String requestId) throws Exception {
		// TODO Auto-generated method stub
		ResponseWrapper<CredentialRequestStatusDto> responseDto = null;
		CredentialRequestStatusDto credentialRequestStatusResponseDto = new CredentialRequestStatusDto();
		try {

			String credentialUrl = env.getProperty(ApiName.CREDENTIAL_STATUS_URL.name()) + requestId;
			URI credentailStatusUri = URI.create(credentialUrl);
			responseDto = residentServiceRestClient.getApi(credentailStatusUri, ResponseWrapper.class,
					tokenGenerator.getToken());
			credentialRequestStatusResponseDto = JsonUtil.readValue(
					JsonUtil.writeValueAsString(responseDto.getResponse()), CredentialRequestStatusDto.class);
			URI dataShareUri = URI.create(credentialRequestStatusResponseDto.getUrl());
			String encryptedData = residentServiceRestClient.getApi(dataShareUri, String.class,
					tokenGenerator.getToken());
			RequestWrapper<CryptomanagerRequestDto> request = new RequestWrapper<>();
			CryptomanagerRequestDto cryptomanagerRequestDto = new CryptomanagerRequestDto();
			cryptomanagerRequestDto.setApplicationId(applicationId);
			cryptomanagerRequestDto.setData(encryptedData);
			cryptomanagerRequestDto.setReferenceId(partnerReferenceId);
			cryptomanagerRequestDto.setPrependThumbprint(isPrependThumbprintEnabled);
			LocalDateTime localdatetime = LocalDateTime.now();
			request.setRequesttime(DateUtils.formatToISOString(localdatetime));
			cryptomanagerRequestDto.setTimeStamp(localdatetime);
			request.setRequest(cryptomanagerRequestDto);
			String response = residentServiceRestClient.postApi(
					env.getProperty(ApiName.DECRYPT_API_URL.name()), MediaType.APPLICATION_JSON, request,
					String.class, tokenGenerator.getToken());
			CryptomanagerResponseDto responseObject = mapper.readValue(response, CryptomanagerResponseDto.class);
			byte[] pdfBytes = CryptoUtil.decodeURLSafeBase64(responseObject.getResponse().getData());
			return pdfBytes;
		} catch (ApisResourceAccessException e) {
			audit.setAuditRequestDto(EventEnum.REQ_CARD_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		} catch (IOException e) {
			audit.setAuditRequestDto(EventEnum.REQ_CARD_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		}
	}

	@Override
	public CredentialRequestStatusResponseDto getStatus(String requestId) {
		ResponseWrapper<CredentialRequestStatusDto> responseDto = null;
		CredentialRequestStatusDto credentialRequestStatusDto = new CredentialRequestStatusDto();
		Map<String, Object> additionalAttributes = new HashedMap();
		CredentialRequestStatusResponseDto credentialRequestStatusResponseDto=new CredentialRequestStatusResponseDto();
		try {
			String credentialUrl = env.getProperty(ApiName.CREDENTIAL_STATUS_URL.name()) + requestId;
			URI credentailStatusUri = URI.create(credentialUrl);
			responseDto =residentServiceRestClient.getApi(credentailStatusUri, ResponseWrapper.class, tokenGenerator.getToken());
			credentialRequestStatusDto = JsonUtil
						.readValue(JsonUtil.writeValueAsString(responseDto.getResponse()), CredentialRequestStatusDto.class);
			credentialRequestStatusResponseDto.setId(credentialRequestStatusDto.getId());
			credentialRequestStatusResponseDto.setRequestId(credentialRequestStatusDto.getRequestId());
			credentialRequestStatusResponseDto.setStatusCode(credentialRequestStatusDto.getStatusCode());
			additionalAttributes.put("RID", credentialRequestStatusResponseDto.getRequestId());
			additionalAttributes.put("status", credentialRequestStatusResponseDto.getStatusCode());
			sendNotification(credentialRequestStatusResponseDto.getId(), NotificationTemplateCode.RS_CRE_STATUS,
					additionalAttributes);

		} catch (ApisResourceAccessException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_STATUS_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		catch(IOException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_STATUS_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (ResidentServiceCheckedException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_REQ_STATUS_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.RESIDENT_SYS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.RESIDENT_SYS_EXCEPTION.getErrorMessage(), e);
		}
		return credentialRequestStatusResponseDto;
	}

	public CredentialReqestDto prepareCredentialRequest(ResidentCredentialRequestDto residentCreDto) {
		CredentialReqestDto crDto=new CredentialReqestDto();
		crDto.setAdditionalData(residentCreDto.getAdditionalData());
		crDto.setCredentialType(residentCreDto.getCredentialType());
		crDto.setEncrypt(residentCreDto.isEncrypt());
		crDto.setId(residentCreDto.getIndividualId());
		crDto.setRecepiant(residentCreDto.getRecepiant());
		crDto.setSharableAttributes(residentCreDto.getSharableAttributes());
		crDto.setUser(residentCreDto.getUser());
		crDto.setIssuer(residentCreDto.getIssuer());
		if (residentCreDto.getEncryptionKey().isEmpty()) {
			crDto.setEncryptionKey(generatePin());
		} else {
			crDto.setEncryptionKey(residentCreDto.getEncryptionKey());
		}
		return crDto;
		
	}


	@Override
	public CredentialCancelRequestResponseDto cancelCredentialRequest(String requestId) {
		ResponseWrapper<CredentialCancelRequestResponseDto> response = new ResponseWrapper<CredentialCancelRequestResponseDto>();
		Map<String, Object> additionalAttributes = new HashedMap();
		CredentialCancelRequestResponseDto credentialCancelRequestResponseDto=new CredentialCancelRequestResponseDto();
		try {
				String credentialReqCancelUrl = env.getProperty(ApiName.CREDENTIAL_CANCELREQ_URL.name()) + requestId;
				URI credentailReqCancelUri = URI.create(credentialReqCancelUrl);
				response = residentServiceRestClient.getApi(credentailReqCancelUri, ResponseWrapper.class,
						tokenGenerator.getToken());
				if (response.getErrors() != null && !response.getErrors().isEmpty()) {
					throw new ResidentCredentialServiceException(response.getErrors().get(0).getErrorCode(),
							response.getErrors().get(0).getMessage());
				}
				credentialCancelRequestResponseDto = JsonUtil.readValue(JsonUtil.writeValueAsString(response.getResponse()),
							CredentialCancelRequestResponseDto.class);
				additionalAttributes.put("RID", credentialCancelRequestResponseDto.getRequestId());
				sendNotification(credentialCancelRequestResponseDto.getId(),
						NotificationTemplateCode.RS_CRE_CANCEL_SUCCESS, additionalAttributes);

		} catch (ApisResourceAccessException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_CANCEL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		catch (IOException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_CANCEL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (ResidentServiceCheckedException e) {
			audit.setAuditRequestDto(EventEnum.CREDENTIAL_CANCEL_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.RESIDENT_SYS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.RESIDENT_SYS_EXCEPTION.getErrorMessage());
		}


		return credentialCancelRequestResponseDto;
	}

	@Override
	public CredentialTypeResponse getCredentialTypes() {
		CredentialTypeResponse credentialTypeResponse=new CredentialTypeResponse();
		URI credentailTypesUri = URI.create(env.getProperty(ApiName.CREDENTIAL_TYPES_URL.name()));
		try {
			credentialTypeResponse=residentServiceRestClient.getApi(credentailTypesUri, CredentialTypeResponse.class, tokenGenerator.getToken());
		} catch (ApisResourceAccessException  e) {
			throw new ResidentCredentialServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		 catch (IOException e) {
				audit.setAuditRequestDto(EventEnum.CREDENTIAL_TYPES_EXCEPTION);
				throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
						ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
			}
		return credentialTypeResponse;
	}

	@Override
	public ResponseWrapper<PartnerCredentialTypePolicyDto> getPolicyByCredentialType(String partnerId,
			String credentialType)
	{
		ResponseWrapper<PartnerCredentialTypePolicyDto> response = new ResponseWrapper<PartnerCredentialTypePolicyDto>();
		Map<String, String> pathsegments = new HashMap<>();
		pathsegments.put("partnerId", partnerId);
		pathsegments.put("credentialType", credentialType);
		try {
			response = residentServiceRestClient.getApi(ApiName.POLICY_REQ_URL, pathsegments,
					ResponseWrapper.class, tokenGenerator.getToken());
		} catch (Exception e) {
			audit.setAuditRequestDto(EventEnum.REQ_POLICY_EXCEPTION);
			throw new ResidentCredentialServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		}
		return response;
	}

	public String generatePin() {
		return RandomStringUtils.randomNumeric(6);
	}


	private NotificationResponseDTO sendNotification(String id,
			NotificationTemplateCode templateTypeCode, Map<String, Object> additionalAttributes)
			throws ResidentServiceCheckedException {
		NotificationRequestDto notificationRequest = new NotificationRequestDto(id, templateTypeCode,
				additionalAttributes);
		return notificationService.sendNotification(notificationRequest);
	}
	/*
	 * private PartnerCredentialTypePolicyResponseDto policyMapper(
	 * PartnerCredentialTypePolicyDto partnerCredentialTypePolicyDto) {
	 * PartnerCredentialTypePolicyResponseDto policy = new
	 * PartnerCredentialTypePolicyResponseDto();
	 * policy.setCr_by(partnerCredentialTypePolicyDto.getCr_by());
	 * policy.setCr_dtimes(partnerCredentialTypePolicyDto.getCr_dtimes());
	 * policy.setCredentialType(partnerCredentialTypePolicyDto.getCredentialType());
	 * policy.setIs_Active(partnerCredentialTypePolicyDto.getIs_Active());
	 * policy.setPartnerId(partnerCredentialTypePolicyDto.getPartnerId());
	 * policy.setPolicyDesc(partnerCredentialTypePolicyDto.getPolicyDesc());
	 * policy.setPolicyId(policyId); policy.setPolicyName(policyName);
	 * policy.setPolicyType(policyType); policy.setPublishDate(publishDate);
	 * policy.setSchema(schema); policy.setStatus(status); policy.setUp_by(up_by);
	 * policy.setUpd_dtimes(upd_dtimes); policy.setVersion(version);
	 * policy.setValidTill(validTill);
	 *
	 * }
	 */

	@Override
	public byte[] getRIDDigitalCard(RIDDigitalCardRequestDto requestDto) {
		try {
			String uin = getUINForRID(requestDto.getIndividualId());
			if (!idAuthService.validateOtp(requestDto.getTransactionID(), uin, requestDto.getOtp())) {
				logger.debug(LoggerFileConstant.SESSIONID.toString(), 
						LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
				audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
			}
			DigitalCardStatusResponseDto digitalCardStatusResponseDto = getDigitialCardStatus(
					requestDto.getIndividualId());
			if(!digitalCardStatusResponseDto.getStatusCode().equals(AVAILABLE)) {
				audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
				throw new ResidentServiceException(
					ResidentErrorCode.DIGITAL_CARD_RID_NOT_FOUND.getErrorCode(),
					ResidentErrorCode.DIGITAL_CARD_RID_NOT_FOUND.getErrorMessage());
			}
			URI dataShareUri = URI.create(digitalCardStatusResponseDto.getUrl());
			byte[] data = residentServiceRestClient.getApi(dataShareUri, byte[].class,
						tokenGenerator.getToken());
			return data;
		} catch (ResidentServiceCheckedException e) {
			audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(
				ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
				ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		} catch (ApisResourceAccessException e) {
			audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(
				ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
				ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		} catch (IOException e) {
			audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(
				ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
				ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (OtpValidationFailedException e) {
			audit.setAuditRequestDto(EventEnum.RID_DIGITAL_CARD_REQ_EXCEPTION);
			throw new ResidentCredentialServiceException(
				ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
				ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage(), e);
		}
	}

	private String getUINForRID(String rid) throws ResidentServiceCheckedException {
		try {
			JSONObject jsonObject = utilitiy.retrieveIdrepoJson(rid);
			return JsonUtil.getJSONValue(jsonObject, IdType.UIN.name());
		} catch (ResidentServiceCheckedException e) {
			throw new DataNotFoundException(e.getErrorCode(),e.getMessage());
		}
	}

	private DigitalCardStatusResponseDto getDigitialCardStatus(String individualId) 
			throws ApisResourceAccessException, IOException {
		String digitialCardStatusUrl = env.getProperty(ApiName.DIGITAL_CARD_STATUS_URL.name()) +
			individualId;
		URI digitialCardStatusUri = URI.create(digitialCardStatusUrl);
		ResponseWrapper<DigitalCardStatusResponseDto> responseDto = 
			residentServiceRestClient.getApi(digitialCardStatusUri, ResponseWrapper.class,
			tokenGenerator.getToken());
		DigitalCardStatusResponseDto digitalCardStatusResponseDto = JsonUtil.readValue(
			JsonUtil.writeValueAsString(responseDto.getResponse()), DigitalCardStatusResponseDto.class);
		return digitalCardStatusResponseDto;
	}
}