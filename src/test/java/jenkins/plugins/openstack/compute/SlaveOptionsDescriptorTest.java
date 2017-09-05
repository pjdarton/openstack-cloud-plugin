package jenkins.plugins.openstack.compute;

import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import static hudson.util.FormValidation.Kind.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.image.ImageService;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.storage.block.VolumeSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * @author ogondza.
 */
public class SlaveOptionsDescriptorTest {
    private static final FormValidation VALIDATION_REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

    public @Rule PluginTestRule j = new PluginTestRule();

    private SlaveOptionsDescriptor d;

    @Before
    public void before() {
        d = (SlaveOptionsDescriptor) j.jenkins.getDescriptorOrDie(SlaveOptions.class);
    }

    @Test
    public void doCheckInstanceCap() throws Exception {
        assertThat(d.doCheckInstanceCap(null, null), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckInstanceCap(null, "3"), hasState(OK, "Inherited value: 3"));
        assertThat(d.doCheckInstanceCap(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckInstanceCap("1", "1"), hasState(OK, null)); // TODO do we want to report def == value ?
        // assertEquals(OK, d.doCheckInstanceCap("0", "1").kind); TODO this can be a handy way to disable the cloud/template temporarily

        assertThat(d.doCheckInstanceCap("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckInstanceCap("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckInstanceCap("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckInstanceCap("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckStartTimeout() throws Exception {
        assertThat(d.doCheckStartTimeout(null, null), hasState(OK, "Inherited value: 600000"));
        assertThat(d.doCheckStartTimeout(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckStartTimeout(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckStartTimeout("1", "1"), hasState(OK, null)); //"Inherited value: 1"

        assertThat(d.doCheckStartTimeout("0", "1"), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckStartTimeout("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckStartTimeout("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckStartTimeout("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckStartTimeout("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckNumExecutors() throws Exception {
        assertThat(d.doCheckNumExecutors(null, null), hasState(OK, "Inherited value: 1"));
        assertThat(d.doCheckNumExecutors(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckNumExecutors(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckNumExecutors("1", "1"), hasState(OK, null)); //"Inherited value: 1"

        assertThat(d.doCheckNumExecutors("0", "1"), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckNumExecutors("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckNumExecutors("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckNumExecutors("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckNumExecutors("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckRetentionTime() throws Exception {
        assertThat(d.doCheckRetentionTime(null, null), hasState(OK, "Inherited value: 30"));
        assertThat(d.doCheckRetentionTime(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckRetentionTime(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckRetentionTime("1", "1"), hasState(OK, null)); //"Inherited value: 1"
        assertThat(d.doCheckRetentionTime("0", "1"), hasState(OK, null));
        assertThat(d.doCheckRetentionTime("-1", null), hasState(OK, "Keep forever"));
        assertThat(d.doCheckRetentionTime("-1", "1"), hasState(OK, "Keep forever"));

        assertThat(d.doCheckRetentionTime("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckRetentionTime("err", "1"), hasState(ERROR, "Not a number"));
    }

    public static TypeSafeMatcher<FormValidation> hasState(final FormValidation.Kind kind, final String msg) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(Description description) {
                description.appendText(kind.toString() + ": " + msg);
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return kind.equals(item.kind) && Objects.equals(item.getMessage(), msg);
            }
        };
    }

    public static TypeSafeMatcher<FormValidation> hasState(final FormValidation expected) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(Description description) {
                description.appendText(expected.kind.toString() + ": " + expected.getMessage());
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return expected.kind.equals(item.kind) && Objects.equals(item.getMessage(), expected.getMessage());
            }
        };
    }

    @Test
    public void doFillImageIdItemsPopulatesImageNamesNotIds() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        when(image.getName()).thenReturn("image-name");

        Openstack os = j.fakeOpenstackFactory();
        doReturn(Collections.singletonMap("image-name", Collections.singletonList(image))).when(os).getImages();

        ListBoxModel list = d.doFillImageIdItems(JCloudsCloud.BootSource.IMAGE.name(), "", "not-needed", "OSurl", "OSid", "OSpwd", "OSzone");
        assertEquals(3, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals(JCloudsCloud.BootSource.IMAGE.toDisplayName() + " " + image.getName(), item.name);
        assertEquals(image.getName(), item.value);
    }

    @Test
    public void doFillImageIdItemsPopulatesVolumeSnapshotNames() {
        VolumeSnapshot volumeSnapshot = mock(VolumeSnapshot.class);
        when(volumeSnapshot.getId()).thenReturn("vs-id");
        when(volumeSnapshot.getName()).thenReturn("vs-name");
        final Collection<VolumeSnapshot> justVolumeSnapshot = Collections.singletonList(volumeSnapshot);

        Openstack os = j.fakeOpenstackFactory();
        when(os.getVolumeSnapshots()).thenReturn(Collections.singletonMap("vs-name", justVolumeSnapshot));

        ListBoxModel list = d.doFillImageIdItems(JCloudsCloud.BootSource.VOLUMESNAPSHOT.name(), "", "existing-vs-name", "OSurl", "OSid", "OSpwd", "OSzone");
        assertEquals(3, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        assertEquals("Second menu entry is the VS OpenStack can see", JCloudsCloud.BootSource.VOLUMESNAPSHOT.toDisplayName() + " vs-name", list.get(1).name);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-name", list.get(1).value);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).name);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).value);
    }

    @Test @Issue("JENKINS-29993")
    public void doFillImageIdItemsAcceptsNullAsImageName() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        when(image.getName()).thenReturn(null);

        OSClient.OSClientV2 osClient = mock(OSClient.OSClientV2.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        final Date issued = new Date();
        final Date expires = new Date(issued.getTime()+24*60*60*1000);
        when(osClient.getAccess().getToken().getExpires()).thenReturn(expires);
        ImageService imageService = mock(ImageService.class);
        when(osClient.images()).thenReturn(imageService);
        doReturn(Collections.singletonList(image)).when(imageService).listAll();

        j.fakeOpenstackFactory(new Openstack(osClient));

        ListBoxModel list = d.doFillImageIdItems(JCloudsCloud.BootSource.IMAGE.name(), "", "", "OSurl", "OSid", "OSpwd", "OSzone");
        assertThat(list.get(0).name, list, Matchers.<ListBoxModel.Option>iterableWithSize(2));
        assertEquals(2, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals(JCloudsCloud.BootSource.IMAGE.toDisplayName()+" image-id", item.name);
        assertEquals("image-id", item.value);

        verify(imageService).listAll();
        verifyNoMoreInteractions(imageService);
    }

    @Test
    public void doCheckImageIdWhenNoValueSet() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage, bootFromImageByDefault;
        bootFromImage= bootFromImageByDefault = JCloudsCloud.BootSource.IMAGE.name();
        final FormValidation expected = VALIDATION_REQUIRED;

        final FormValidation actual = d.doCheckImageId("", "", bootFromImage, bootFromImageByDefault, urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenDefaultAvailable() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImageByDefault;
        bootFromImageByDefault = JCloudsCloud.BootSource.IMAGE.name();
        final FormValidation expected = FormValidation.ok("Inherited value: aCloudDefault");

        final FormValidation actual = d.doCheckImageId("", "aCloudDefault", "", bootFromImageByDefault, urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenDefaultAvailableForSameBootSource() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage, bootFromImageByDefault;
        bootFromImage= bootFromImageByDefault = JCloudsCloud.BootSource.IMAGE.name();
        final FormValidation expected = FormValidation.ok("Inherited value: aCloudDefault");

        final FormValidation actual = d.doCheckImageId("", "aCloudDefault", bootFromImage, bootFromImageByDefault, urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenDefaultSetButForWrongBootSource() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage, bootFromVSByDefault;
        bootFromImage = JCloudsCloud.BootSource.IMAGE.name();
        bootFromVSByDefault = JCloudsCloud.BootSource.VOLUMESNAPSHOT.name();
        final FormValidation expected = VALIDATION_REQUIRED;

        final FormValidation actual = d.doCheckImageId("", "aCloudDefaultVS", bootFromImage, bootFromVSByDefault, urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenImageIsNotFoundInOpenstack() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage;
        bootFromImage= JCloudsCloud.BootSource.IMAGE.name();
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageNotFound")).thenReturn(Collections.EMPTY_LIST);
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.error(JCloudsCloud.BootSource.IMAGE.toDisplayName()+" \"imageNotFound\" not found.");

        final FormValidation actual = d.doCheckImageId("imageNotFound", "", bootFromImage, "", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenOneImageIsFound() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage;
        bootFromImage= JCloudsCloud.BootSource.IMAGE.name();
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageFound")).thenReturn(Collections.singletonList("imageFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = d.doCheckImageId("imageFound", "", bootFromImage, "", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenMultipleImagesAreFoundForTheName() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromImage;
        bootFromImage= JCloudsCloud.BootSource.IMAGE.name();
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageAmbiguous")).thenReturn(Arrays.asList("imageAmbiguousId1", "imageAmbiguousId2"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.warning(JCloudsCloud.BootSource.IMAGE.toDisplayName()+" \"imageAmbiguous\" is ambiguous.");

        final FormValidation actual = d.doCheckImageId("imageAmbiguous", "", bootFromImage, "", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat("imageAmbiguous", actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenOneVolumeSnapshotIsFound() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final String bootFromVS;
        bootFromVS= JCloudsCloud.BootSource.VOLUMESNAPSHOT.name();
        final Openstack os = mock(Openstack.class);
        when(os.getVolumeSnapshotIdsFor("vsFound")).thenReturn(Collections.singletonList("vsFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = d.doCheckImageId("vsFound", "", bootFromVS, "", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat("vsFound", actual, hasState(expected));
    }

    @Test
    public void doFillAvailabilityZoneItemsGivenAZsThenPopulatesList() {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        final AvailabilityZone az2 = mock(AvailabilityZone.class, "az2");
        when(az1.getZoneName()).thenReturn("az1Name");
        when(az2.getZoneName()).thenReturn("az2Name");
        final List<AvailabilityZone> azs = Arrays.asList(az1, az2);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();

        final ComboBoxModel actual = d.doFillAvailabilityZoneItems("az2Name", "OSurl", "OSid", "OSpwd", "OSzone");

        assertEquals(2, actual.size());
        final String az1Option = actual.get(0);
        assertThat(az1Option, equalTo("az1Name"));
        final String az2Option = actual.get(1);
        assertThat(az2Option, equalTo("az2Name"));
    }

    @Test
    public void doFillAvailabilityZoneItemsGivenNoSupportForAZsThenGivesEmptyList() {
        final Openstack os = j.fakeOpenstackFactory();
        doThrow(new RuntimeException("OpenStack said no")).when(os).getAvailabilityZones();

        final ComboBoxModel actual = d.doFillAvailabilityZoneItems("az2Name", "OSurl", "OSid", "OSpwd", "OSzone");

        assertEquals(0, actual.size());
    }

    @Test
    public void doCheckAvailabilityZoneGivenAZThenReturnsOK() throws Exception {
        final String value = "chosenAZ";
        final String def = "";
        final Openstack os = j.fakeOpenstackFactory();
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", "OSurl", "OSid", "OSid", "OSpwd", "OSpwd", "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
        verifyNoMoreInteractions(os);
    }

    @Test
    public void doCheckAvailabilityZoneGivenDefaultAZThenReturnsOKWithDefault() throws Exception {
        final String value = "";
        final String def = "defaultAZ";
        final Openstack os = j.fakeOpenstackFactory();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", "OSurl", "OSid", "OSid", "OSpwd", "OSpwd", "OSzone", "OSzone");

        assertThat(actual, hasState(OK, "Inherited value: " + def));
        verifyNoMoreInteractions(os);
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndOnlyOneZoneToChooseFromThenReturnsOK() throws Exception {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        when(az1.getZoneName()).thenReturn("az1Name");
        final List<AvailabilityZone> azs = Arrays.asList(az1);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", "OSurl", "OSid", "OSid", "OSpwd", "OSpwd", "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndNoSupportForAZsThenReturnsOK() throws Exception {
        final Openstack os = j.fakeOpenstackFactory();
        doThrow(new RuntimeException("OpenStack said no")).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", "OSurl", "OSid", "OSid", "OSpwd", "OSpwd", "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndMultipleZoneToChooseFromThenReturnsWarning() throws Exception {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        final AvailabilityZone az2 = mock(AvailabilityZone.class, "az2");
        when(az1.getZoneName()).thenReturn("az1Name");
        when(az2.getZoneName()).thenReturn("az2Name");
        final List<AvailabilityZone> azs = Arrays.asList(az1, az2);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.warning("Ambiguity warning: Multiple zones found.");

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", "OSurl", "OSid", "OSid", "OSpwd", "OSpwd", "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void fillDependencies() throws Exception {
        List<String> expected = Arrays.asList(
                "../endPointUrl", "../../endPointUrl",
                "../identity", "../../identity",
                "../credential", "../../credential",
                "../zone", "../../zone"
        );
        List<String> expectedImageId = new ArrayList<String>();
        expectedImageId.add("bootSource");
        expectedImageId.add("../../slaveOptions/copyOfBootSource");
        expectedImageId.addAll(expected);

        assertThat(getFillDependencies("keyPairName"), equalTo(expected));
        assertThat(getFillDependencies("floatingIpPool"), equalTo(expected));
        assertThat(getFillDependencies("hardwareId"), equalTo(expected));
        assertThat(getFillDependencies("imageId"), equalTo(expectedImageId));
        assertThat(getFillDependencies("networkId"), equalTo(expected));

        assertFillWorks("floatingIpPool");
        assertFillWorks("hardwareId");
        assertFillWorks("imageId");
        assertFillWorks("networkId");
        assertFillWorks("keyPairName");
    }

    private void assertFillWorks(String attribute) throws Exception {
        final String END_POINT = "END_POINT-" + attribute;
        final String IDENTITY = "IDENTITY";
        final String CREDENTIAL = "CREDENTIAL";
        final String REGION = "REGION";
        final String QUERY_STRING = String.format(
                "?endPointUrl=%s&identity=%s&credential=%s&zone=%s&bootSource=%s",
                END_POINT, IDENTITY, CREDENTIAL, REGION, JCloudsCloud.BootSource.IMAGE
        );

        String contextPath = j.getURL().getFile();
        String fillUrl = getFillUrl(attribute);
        assertThat(fillUrl, startsWith(contextPath));
        fillUrl = fillUrl.substring(contextPath.length());

        Openstack.FactoryEP factory = j.mockOpenstackFactory();
        when(
                factory.getOpenstack(anyString(), anyString(), anyString(), anyString())
        ).thenThrow(
                new AuthenticationException("Noone cares as we are testing if correct credentials are passed in", 42)
        );

        j.createWebClient().goTo(fillUrl + QUERY_STRING, "application/json");

        verify(factory).getOpenstack(eq(END_POINT), eq(IDENTITY), eq(CREDENTIAL), eq(REGION));
        verifyNoMoreInteractions(factory);
    }

    private List<String> getFillDependencies(final String field) throws Exception {
        final HashMap<String, Object> map = getFillData(field);

        List<String> out = new ArrayList<>(Arrays.asList(((String) map.get("fillDependsOn")).split(" ")));
        assertTrue(out.contains(field));
        out.remove(field);
        return out;
    }

    private String getFillUrl(final String field) throws Exception {
        final HashMap<String, Object> map = getFillData(field);

        return (String) map.get("fillUrl");
    }

    private HashMap<String, Object> getFillData(final String field) throws Exception {
        final HashMap<String, Object> map = new HashMap<>();
        // StaplerRequest required
        j.executeOnServer(new Callable<Void>() {
            @Override public Void call() throws Exception {
                SlaveOptionsDescriptor d = (SlaveOptionsDescriptor) j.jenkins.getDescriptorOrDie(SlaveOptions.class);
                d.calcFillSettings(field, map);
                return null;
            }
        });
        return map;
    }
}
