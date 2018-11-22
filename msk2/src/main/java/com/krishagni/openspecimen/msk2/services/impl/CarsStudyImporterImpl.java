package com.krishagni.openspecimen.msk2.services.impl;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.krishagni.catissueplus.core.administrative.domain.Site;
import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.administrative.events.SiteDetail;
import com.krishagni.catissueplus.core.administrative.events.UserDetail;
import com.krishagni.catissueplus.core.administrative.repository.SiteListCriteria;
import com.krishagni.catissueplus.core.administrative.services.SiteService;
import com.krishagni.catissueplus.core.administrative.services.UserService;
import com.krishagni.catissueplus.core.biospecimen.domain.AliquotSpecimensRequirement;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolEvent;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenRequirement;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionProtocolDetail;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionProtocolEventDetail;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionProtocolSiteDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenRequirementDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.services.CollectionProtocolService;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.domain.ExternalAppId;
import com.krishagni.catissueplus.core.common.errors.ErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.events.UserSummary;
import com.krishagni.catissueplus.core.common.util.ConfigUtil;
import com.krishagni.catissueplus.core.common.util.CsvFileWriter;
import com.krishagni.catissueplus.core.common.util.EmailUtil;
import com.krishagni.catissueplus.core.common.util.Status;
import com.krishagni.catissueplus.core.common.util.Utility;
import com.krishagni.catissueplus.core.importer.domain.ImportJob;
import com.krishagni.openspecimen.msk2.domain.CarsErrorCode;
import com.krishagni.openspecimen.msk2.events.CarsStudyDetail;
import com.krishagni.openspecimen.msk2.events.CollectionDetail;
import com.krishagni.openspecimen.msk2.events.ImportLogDetail;
import com.krishagni.openspecimen.msk2.events.TimepointDetail;
import com.krishagni.openspecimen.msk2.repository.CarsStudyReader;
import com.krishagni.openspecimen.msk2.repository.impl.CarsStudyReaderImpl;
import com.krishagni.openspecimen.msk2.services.CarsStudyImporter;

public class CarsStudyImporterImpl implements CarsStudyImporter {
	private static final Log logger = LogFactory.getLog(CarsStudyImporterImpl.class);

	private DaoFactory daoFactory;

	private UserService userSvc;

	private CollectionProtocolService cpSvc;

	public void setDaoFactory(DaoFactory daoFactory) {
		this.daoFactory = daoFactory;
	}

	public void setUserSvc(UserService userSvc) {
		this.userSvc = userSvc;
	}

	public void setCpSvc(CollectionProtocolService cpSvc) {
		this.cpSvc = cpSvc;
	}

	@Override
	public void importStudies() {
		ImportJob latestJob = CarsImportJobUtil.getInstance().getLatestJob(IMPORTER_NAME);
		if (latestJob != null && latestJob.isInProgress()) {
			logger.error("A prior submitted CARS biospecimen importer job is in progress: " + latestJob.getId());
			return;
		}

		ImportJob importJob = CarsImportJobUtil.getInstance().createJob(IMPORTER_NAME);
		File importLogFile = getImportLogFile(importJob);

		int totalStudies = 0, failedStudies = 0;
		CarsStudyReader reader = null;
		CsvFileWriter importLogWriter = null;
		try {
			Date lastUpdated = latestJob != null ? latestJob.getEndTime() : null;
			reader = getStudyReader();
			importLogWriter = createImportLogWriter(importLogFile);

			CarsStudyDetail study;
			while ((study = reader.next()) != null) {
				try {
					importStudy(lastUpdated, study);
				} catch (OpenSpecimenException ose) {
					logger.error("Error importing CARS study - " + study.getIrbNumber(), ose);
					++failedStudies;
				} finally {
					++totalStudies;
					logImportDetail(study, importLogWriter);
				}
			}
		} catch (Exception e) {
			logger.error("Error importing CARS studies", e);
		} finally {
			IOUtils.closeQuietly(reader);
			IOUtils.closeQuietly(importLogWriter);
			CarsImportJobUtil.getInstance().finishJob(importJob, totalStudies, failedStudies, null); // TODO;
			notifyUsers(importJob, importLogFile);
		}
	}

	@PlusTransactional
	private void importStudy(Date lastUpdated, CarsStudyDetail inputStudy) {
		CollectionProtocol existingCp = getCpFromDb(inputStudy.getIrbNumber());
		if (existingCp == null) {
			createCp(inputStudy);
		} else {
			updateCp(lastUpdated, existingCp, inputStudy);
		}
	}

	private void createCp(CarsStudyDetail inputStudy) {
		Long piId = createUserIfAbsent(inputStudy.getPiAddress(), inputStudy);
		UserSummary pi = new UserSummary();
		pi.setId(piId);

		CollectionProtocolDetail cpDetail = new CollectionProtocolDetail();
		cpDetail.setShortTitle(inputStudy.getIrbNumber());
		cpDetail.setTitle(inputStudy.getIrbNumber());
		cpDetail.setPrincipalInvestigator(pi);
		cpDetail.setCpSites(getCpSites());

		cpDetail = response(cpSvc.createCollectionProtocol(request(cpDetail)), inputStudy);
		saveExtId(CollectionProtocol.class, inputStudy.getIrbNumber(), cpDetail.getId());

		for (TimepointDetail timepoint : inputStudy.getTimepoints()) {
			createEvent(cpDetail.getShortTitle(), timepoint);
		}
	}

	private void updateCp(Date lastUpdated, CollectionProtocol existingCp, CarsStudyDetail inputStudy) {
		updateCp(existingCp, inputStudy);

		Map<Long, CollectionProtocolEvent> cpEvents = existingCp.getCollectionProtocolEvents().stream()
			.collect(Collectors.toMap(CollectionProtocolEvent::getId, cpe -> cpe));

		for (TimepointDetail timepoint : inputStudy.getTimepoints()) {
			ExternalAppId eventId = getOsIdFromExtId(CollectionProtocolEvent.class, timepoint.getId());
			if (eventId == null || eventId.getOsId() == null) {
				createEvent(existingCp.getShortTitle(), timepoint);
			} else {
				CollectionProtocolEvent existingEvent = cpEvents.remove(eventId.getOsId());
				updateEvent(lastUpdated, existingEvent, timepoint);
			}
		}

		cpEvents.values().forEach(this::closeEvent);
	}

	private void updateCp(CollectionProtocol existingCp, CarsStudyDetail inputStudy) {
		boolean piChanged = !existingCp.getPrincipalInvestigator()
			.getEmailAddress().equalsIgnoreCase(inputStudy.getPiAddress());

		if (!piChanged) {
			return;
		}

		CollectionProtocolDetail cpDetail = CollectionProtocolDetail.from(existingCp);
		Long piId = createUserIfAbsent(inputStudy.getPiAddress(), inputStudy);

		UserSummary pi = new UserSummary();
		pi.setId(piId);
		cpDetail.setPrincipalInvestigator(pi);

		response(cpSvc.updateCollectionProtocol(request(cpDetail)), inputStudy);
	}

	private void createEvent(String cpShortTitle, TimepointDetail timepoint) {
		CollectionProtocolEventDetail event = toEvent(cpShortTitle, timepoint);
		event = response(cpSvc.addEvent(request(event)), timepoint);
		saveExtId(CollectionProtocolEvent.class, timepoint.getId(), event.getId());

		for (CollectionDetail collection : timepoint.getCollections()) {
			createRequirement(event.getId(), collection);
		}
	}

	private void updateEvent(Date lastUpdated, CollectionProtocolEvent existingEvent, TimepointDetail timepoint) {
		if (lastUpdated == null || lastUpdated.before(timepoint.getUpdateTime())) {
			CollectionProtocolEventDetail event = CollectionProtocolEventDetail.from(existingEvent);
			event.setEventLabel(toEventLabel(timepoint));
			response(cpSvc.updateEvent(request(event)), timepoint);
		}

		Map<Long, SpecimenRequirement> requirements = existingEvent.getTopLevelAnticipatedSpecimens().stream()
			.collect(Collectors.toMap(SpecimenRequirement::getId, sr -> sr));

		for (CollectionDetail collection : timepoint.getCollections()) {
			ExternalAppId srId = getOsIdFromExtId(SpecimenRequirement.class, collection.getId());
			if (srId == null || srId.getOsId() == null) {
				createRequirement(existingEvent.getId(), collection);
			} else {
				SpecimenRequirement existingSr = requirements.remove(srId.getOsId());
				updateRequirement(lastUpdated, srId, existingSr, collection);
			}
		}

		requirements.values().forEach(this::closeRequirement);
	}

	private void closeEvent(CollectionProtocolEvent existingEvent) {
		if (existingEvent.isClosed()) {
			return;
		}

		CollectionProtocolEventDetail event = CollectionProtocolEventDetail.from(existingEvent);
		event.setActivityStatus(Status.ACTIVITY_STATUS_CLOSED.getStatus());
		response(cpSvc.updateEvent(request(event)));
	}

	private CollectionProtocolEventDetail toEvent(String cpShortTitle, TimepointDetail timepoint) {
		CollectionProtocolEventDetail event = new CollectionProtocolEventDetail();
		event.setCpShortTitle(cpShortTitle);
		event.setEventLabel(toEventLabel(timepoint));
		event.setClinicalDiagnosis("Not Specified");
		event.setClinicalStatus("Not Specified");
		return event;
	}

	private String toEventLabel(TimepointDetail timepoint) {
		return timepoint.getCycle() + " " + timepoint.getName();
	}

	private SpecimenRequirementDetail createRequirement(Long eventId, CollectionDetail collection) {
		SpecimenRequirementDetail sr = toSr(eventId, collection);
		sr = response(cpSvc.addSpecimenRequirement(request(sr)), collection);
		saveExtId(SpecimenRequirement.class, collection.getId(), sr.getId());

		AliquotSpecimensRequirement aliquotReq = new AliquotSpecimensRequirement();
		aliquotReq.setCpShortTitle(sr.getCpShortTitle());
		aliquotReq.setEventLabel(sr.getEventLabel());
		aliquotReq.setNoOfAliquots(1);
		aliquotReq.setQtyPerAliquot(sr.getInitialQty());
		aliquotReq.setParentSrId(sr.getId());
		aliquotReq.setStorageType("Manual");
		response(cpSvc.createAliquots(request(aliquotReq)), collection);

		return sr;
	}

	private void updateRequirement(Date lastUpdated, ExternalAppId srId, SpecimenRequirement existingSr, CollectionDetail collection) {
		if (lastUpdated != null && !lastUpdated.before(collection.getUpdateTime())) {
			return;
		}

		if (!existingSr.getSpecimenType().equalsIgnoreCase(collection.getType())) {
			existingSr.close();
			srId.setOsId(-1 * existingSr.getId());
			createRequirement(existingSr.getCollectionProtocolEvent().getId(), collection);
			return;
		}

		SpecimenRequirementDetail sr = SpecimenRequirementDetail.from(existingSr);
		sr.setName(collection.getName());
		if (!sr.getType().equalsIgnoreCase(collection.getType())) {
			sr.setSpecimenClass(getSpecimenClass(collection.getType()));
			sr.setType(collection.getType());
		}

		sr.setCollectionContainer(collection.getContainer());
		sr.setActivityStatus(Status.ACTIVITY_STATUS_ACTIVE.getStatus());

		response(cpSvc.updateSpecimenRequirement(request(sr)), collection);
	}

	private void closeRequirement(SpecimenRequirement existingSr) {
		if (existingSr.isClosed()) {
			return;
		}

		SpecimenRequirementDetail sr = SpecimenRequirementDetail.from(existingSr);
		sr.setActivityStatus(Status.ACTIVITY_STATUS_CLOSED.getStatus());
		response(cpSvc.updateSpecimenRequirement(request(sr)));
	}

	private SpecimenRequirementDetail toSr(Long eventId, CollectionDetail collection) {
		SpecimenRequirementDetail sr = new SpecimenRequirementDetail();
		sr.setEventId(eventId);
		sr.setLineage(Specimen.NEW);
		sr.setName(collection.getName());
		sr.setSpecimenClass(getSpecimenClass(collection.getType()));
		sr.setType(collection.getType());
		sr.setInitialQty(null);
		sr.setCollectionContainer(collection.getContainer());
		sr.setCollectionProcedure(Specimen.NOT_SPECIFIED);
		sr.setAnatomicSite(Specimen.NOT_SPECIFIED);
		sr.setLaterality(Specimen.NOT_SPECIFIED);
		sr.setPathology(Specimen.NOT_SPECIFIED);
		sr.setStorageType("Virtual");
		return sr;
	}

	private String getSpecimenClass(String type) {
		return daoFactory.getPermissibleValueDao().getSpecimenClass(type);
	}

	private Long createUserIfAbsent(String piAddress, ImportLogDetail log) {
		User existingUser = daoFactory.getUserDao().getUserByEmailAddress(piAddress);
		return existingUser == null ? createUser(piAddress, log) : existingUser.getId();
	}

	private Long createUser(String piAddress, ImportLogDetail log) {
		UserDetail input = new UserDetail();
		input.setEmailAddress(piAddress);
		input.setDomainName(DEF_DOMAIN);
		input.setInstituteName(DEF_INSTITUTE);

		String[] parts = piAddress.split("@");
		input.setLastName(parts[0]);
		return response(userSvc.createUser(request(input)), log).getId();
	}

	private List<CollectionProtocolSiteDetail> getCpSites() {
		List<Site> sites = daoFactory.getSiteDao().getSites(new SiteListCriteria().institute(DEF_INSTITUTE));
		return sites.stream().map(site -> {
			CollectionProtocolSiteDetail cpSite = new CollectionProtocolSiteDetail();
			cpSite.setSiteName(site.getName());
			return cpSite;
		}).collect(Collectors.toList());
	}

	private void saveExtId(Class<?> klass, String extId, Long osId) {
		ExternalAppId externalAppId = new ExternalAppId();
		externalAppId.setAppName(EXT_APP_NAME);
		externalAppId.setEntityName(klass.getName());
		externalAppId.setExternalId(extId);
		externalAppId.setOsId(osId);
		daoFactory.getExternalAppIdDao().saveOrUpdate(externalAppId);
	}

	private ExternalAppId getOsIdFromExtId(Class<?> klass, String extId) {
		return daoFactory.getExternalAppIdDao().getByExternalId(EXT_APP_NAME, klass.getName(), extId);
	}

	private CollectionProtocol getCpFromDb(String shortTitle) {
		return daoFactory.getCollectionProtocolDao().getCpByShortTitle(shortTitle);
	}

	private File getImportLogFile(ImportJob job) {
		String filename = "report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(Calendar.getInstance().getTime()) + ".csv";
		return new File(getImportLogsDir(job), filename);
	}

	private File getImportLogsDir(ImportJob job) {
		File dir = new File(ConfigUtil.getInstance().getDataDir(), "bulk-import");
		if (!dir.exists()) {
			dir.mkdirs();
		}

		dir = new File(dir, "jobs");
		if (!dir.exists()) {
			dir.mkdirs();
		}

		dir = new File(dir, job.getId().toString());
		if (!dir.exists()) {
			dir.mkdirs();
		}

		return dir;
	}

	private CsvFileWriter createImportLogWriter(File file) {
		CsvFileWriter writer = CsvFileWriter.createCsvFileWriter(file);
		writer.writeNext(IMPORT_LOGS_FILE_COLUMNS);
		return writer;
	}

	private void logImportDetail(CarsStudyDetail study, CsvFileWriter logWriter)
	throws IOException  {
		for (TimepointDetail timepoint : study.getTimepoints()) {
			for (CollectionDetail collection : timepoint.getCollections()) {
				List<String> row = new ArrayList<>();
				row.add(study.getIrbNumber());
				row.add(study.getFacility());
				row.add(study.getPiAddress());
				row.add(timepoint.getId());
				row.add(timepoint.getCycle());
				row.add(timepoint.getName());
				row.add(collection.getId());
				row.add(collection.getName());
				row.add(collection.getType());
				row.add(collection.getContainer());

				if (study.isErroneous() || timepoint.isErroneous() || collection.isErroneous()) {
					row.add("Failed");
					row.add(getError(study, timepoint, collection));
				} else {
					row.add("Success");
					row.add("");
				}

				logWriter.writeNext(row.toArray(new String[0]));
			}
		}

		logWriter.flush();
	}

	private String getError(ImportLogDetail ... args) {
		if (args == null) {
			return null;
		}

		return Arrays.stream(args).filter(ImportLogDetail::isErroneous)
			.map(ImportLogDetail::getError)
			.collect(Collectors.joining(";"));
	}
	
	private void notifyUsers(ImportJob job, File logsFile) {
		String date = Utility.getDateString(job.getCreationTime());

		Map<String, Object> emailProps = new HashMap<>();
		emailProps.put("$subject", new String[] { date });
		emailProps.put("date", date);
		emailProps.put("ccAdmin", false);

		emailProps.put("jobId",         job.getId());
		emailProps.put("startTime",     job.getCreationTime());
		emailProps.put("endTime",       job.getEndTime());
		emailProps.put("totalStudies",  job.getTotalRecords());
		emailProps.put("failedStudies", job.getFailedRecords());
		emailProps.put("passedStudies", job.getTotalRecords() - job.getFailedRecords());

		File[] attachments = new File[] { logsFile };
		for (User user : getNotifUsers()) {
			emailProps.put("rcpt", user);
			EmailUtil.getInstance().sendEmail(MSK2_CARS_IMPORT_JOB, new String[] {user.getEmailAddress()}, attachments, emailProps);
		}
	}

	@PlusTransactional
	private List<User> getNotifUsers() {
		List<User> systemAdmins = daoFactory.getUserDao().getSuperAndInstituteAdmins(null);

		String itAdminEmailId = ConfigUtil.getInstance().getItAdminEmailId();
		if (StringUtils.isNotBlank(itAdminEmailId)) {
			User itAdmin = new User();
			itAdmin.setFirstName("IT");
			itAdmin.setLastName("Admin");
			itAdmin.setEmailAddress(itAdminEmailId);
			systemAdmins.add(itAdmin);
		}

		return systemAdmins;
	}

	private CarsStudyReader getStudyReader() {
		return new CarsStudyReaderImpl(
			CarsImportJobUtil.getInstance().getDbUrl(),
			CarsImportJobUtil.getInstance().getDbUser(),
			CarsImportJobUtil.getInstance().getDbPassword()
		);
	}

	private <T> RequestEvent<T> request(T payload) {
		return new RequestEvent<>(payload);
	}

	private <T> T response(ResponseEvent<T> resp, ImportLogDetail log) {
		if (!resp.isSuccessful()) {
			log.setError(resp.getError().getMessage());
		}

		return response(resp);
	}

	private <T> T response(ResponseEvent<T> resp) {
		resp.throwErrorIfUnsuccessful();
		return resp.getPayload();
	}

	private static final String IMPORTER_NAME = "mskcc2_cars_study_importer";

	private static final String EXT_APP_NAME = "CARS";

	private static final String DEF_INSTITUTE = "MSKCC";

	private static final String DEF_DOMAIN = "openspecimen";

	private final static String MSK2_CARS_IMPORT_JOB = "msk2_cars_import_job";

	private final static String[] IMPORT_LOGS_FILE_COLUMNS = {
		"IrbNumber", "Facility", "PiAddress",
		"CycleName", "TimepointName", "ProcedureName",
		"SpecimenType", "CollectionContainer", "TimepointID",
		"PVPID", "Status", "Error"
	};
}
