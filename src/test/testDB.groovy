// Test local PostgreSQL DB connection.
// Run this script as follows:
//   groovy -cp .:${javalib}/postgresql-42.2.8.jar testDB.groovy
//
import java.io.*
import groovy.sql.Sql

// LOAD PROPERTIES
def props = new Properties()
def configStream = new FileInputStream('resources/db.properties')
props.load(configStream)
configStream.close()

def readUser = props.readUser
def url = props.url
def user = props.user
def password = props.password
def driverClassName = props.driverClassName

// READING
println("Test: READ counts from database...")

def SQL = Sql.newInstance(url, readUser, password, driverClassName)

def jwst = SQL.firstRow("select count(*) from sia.jwst").count
println("count(jwst) = ${jwst}")
def jaguar = SQL.firstRow("select count(*) from sia.jaguar").count
println("count(jaguar) = ${jaguar}")

SQL.close()


// WRITING
println("Test: WRITE to database...")

SQL = Sql.newInstance(url, user, password, driverClassName)

SQL.execute(
'''insert into sia.jwst (dataproduct_type, calib_level, target_name, obs_id, obs_collection, obs_publisher_did, access_url, access_format, access_estsize, s_ra, s_dec, s_resolution, s_xel1, s_xel2, t_min, t_exptime, o_ucd, facility_name, instrument_name, obs_creator_name, spat_lolimit1, spat_hilimit1, spat_lolimit2, spat_hilimit2, im_scale, im_ra1, im_dec1, im_ra2, im_dec2, im_ra3, im_dec3, im_ra4, im_dec4, im_naxes, im_naxis1, im_naxis2, im_nsubarrays, im_pixtype, im_wcsaxes1, im_wcsaxes2, file_name, file_path, is_public, equinox, radesys, nircam_channel, nircam_module, filter, pupil, gmt_date)
   values ('image', 3, 'goods_south', 'FAKE_OBS_ID_1', 'JWST/images', 'ivo://astrolabe.arizona.edu/jwst', '/external/goods_s_F090W_2018_08_29.fits', 'application/fits', 908847360, 53.16468333333, -27.78311111111, 0.034, 16160, 14060, 58355.0, 1347.0, 'phot.count', 'JWST', 'NIRCam-A', 'JWST', 53.09734515736672, 53.249657226210246, -27.859794716969816, -27.742626880332292, 0.0367423461417462, 53.249657226210246, -27.859785032452066, 53.24956571547702, -27.742626880332292, 53.097417675748574, -27.74263651698225, 53.09734515736672, -27.859794716969816, 2, 16160, 14060, 1, 'float', 'RA---TAN', 'DEC--TAN', 'goods_s_F090W_2018_08_29.fits', '/images/goods_s_F090W_2018_08_29.fits', 0, 2000.0, 'ICRS', 'SHORT', 'A', 'F090W', 'CLEAR', '2018-08-29T12:41:07');'''
)

SQL.close()

