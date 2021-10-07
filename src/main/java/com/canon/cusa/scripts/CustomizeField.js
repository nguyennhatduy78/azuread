//Compulsory
function userPrincipalName (userdata){
    var emplid = userdata.get("uid").toLowerCase();
    var companyName = userdata.get("companyName");
    var domainSuffix = userdata.get("domainSuffix");
    var result = "";
    switch(companyName){
        case "CUSA":
        case "CSAI":
        case "CDB":
        case "MCS":
            result = emplid+"_cusa.canon.com";
            break;
        case "CSA": result = emplid+"_csa.canon.com"; break;
        case "CFS": result = emplid+"_cfs.canon.com"; break;
        case "CITS": result = emplid+"_cits.canon.com"; break;
        case "CCI": result = emplid+"_canada.canon.com"; break;
        case "CMEX": result = emplid+"_canon.com.mx"; break;
        case "CPAS": result = emplid+"_canon.com.pa"; break;
        case "CCH": result = emplid+"_canon.cl"; break;
        case "CVI": result = emplid+"_cvi.canon.com"; break;
        default: result = emplid+"_cusa.canon.com"; break;
    }
    return result+domainSuffix;
}

function mailNickname(userdata){
    return userdata.get("surname");
}
function displayName(userdata){
    if(userdata.get("cn") === ""){
        return userdata.get("givenName") + " "+userdata.get("middleName") +" "+userdata.get("surname");
    }
    if(!userdata.get("cn").contains(",")){
        return userdata.get("givenName") + " "+userdata.get("middleName") +" "+userdata.get("surname");
    }
    return userdata.get("cn").split(",")[1] +" "+userdata.get("cn").split(",")[0];
}


//Validate user data from csv
function validate(userdata){
    //Data validation here, if match return true, else return false
    return true;
}

//Additional logic field processing
function random(){
    var char_pool = [0,1,3,4,5,6,7,8,9,"a","b","c","d","e","f","A","B","C","D","E","F"];
    var today = new Date();
    var dateTime = today.getMonth()+""+today.getDate()+""+today.getHours()+""+today.getMinutes();
    var result = "";
    for (var i = 0; i < 16-dateTime.length; i++) {
        result+= char_pool[Math.floor(Math.random()*(char_pool.length))];
    }
    return result.concat(dateTime);
}

function UUID(){
    return random();
}
function serviceCatUUID(tmpdata){
    return tmpdata.get("UUID").concat(tmpdata.get("UUID"));
}
function boxAddress(tmpdata){
    return tmpdata.get("city")+", "+tmpdata.get("state");
}
function previousEmail(tmpdata){
    if(tmpdata.get("currentEmail")===tmpdata.get("newEmail")){
        return null;
    }
    return tmpdata.get("currentEmail");
}
