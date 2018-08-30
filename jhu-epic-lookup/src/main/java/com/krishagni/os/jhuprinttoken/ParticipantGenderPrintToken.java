package com.krishagni.os.jhuprinttoken;

import org.apache.commons.lang.StringUtils;

import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.common.domain.AbstractLabelTmplToken;
import com.krishagni.catissueplus.core.common.domain.LabelTmplToken;

public class ParticipantGenderPrintToken extends AbstractLabelTmplToken implements LabelTmplToken {

	@Override
	public String getName() {
		return "participant_gender";
	}

	@Override
	public String getReplacement(Object object) {
		Specimen specimen = (Specimen) object;
		String gender = specimen.getRegistration().getParticipant().getGender();
		
		return StringUtils.isNotBlank(gender) ? gender : StringUtils.EMPTY;
	}
	
}
